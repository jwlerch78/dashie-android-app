package com.dashieapp.Dashie.sidebar

// BRIDGE-DYNAMIC: window.$jsFn — LayoutCanvasInputHandler_* callback name chosen per sidebar context (full-mode layout canvas)

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.dashieapp.Dashie.R
import kotlinx.coroutines.*
import com.dashieapp.Dashie.edition.brandName

/**
 * Orchestrator for the native sidebar strip and popout menus.
 *
 * Manages:
 * - Strip visibility (pinned vs. overlay vs. hidden)
 * - Lock state polling (2s interval)
 * - Auto-hide timer (15s for overlay mode and popouts)
 * - Popout creation/dismissal via SidebarPopoutManager
 * - D-pad navigation on TV devices
 *
 * Mirrors JS dash-menu.js behavior.
 */
class NativeSidebarController(
    private val activity: Activity,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "NativeSidebar"
        private const val LOCK_POLL_MS = 2_000L
        private const val AUTO_HIDE_MS = 15_000L
        // Mirror SELECTION_TIMEOUT (config.js, 20s): after d-pad inactivity,
        // fade the sidebar highlight like the widget focus ring does. A pinned
        // sidebar stays visible but loses its highlight; an unpinned one is
        // usually already gone via AUTO_HIDE_MS first.
        private const val HIGHLIGHT_TIMEOUT_MS = 20_000L
        const val SIDEBAR_WIDTH_DP = 69
    }

    interface LockCallbacks {
        fun isAppLocked(): Boolean
        fun showLockDialog()
        fun showUnlockDialog()
        fun showPinRecoveryDialog()
    }

    interface StateCallbacks {
        fun isDashBarPinned(): Boolean
        fun setDashBarPinned(pinned: Boolean)
        fun isSidebarEnabled(): Boolean
        fun onSidebarDismissed()
    }

    interface DeviceControlCallbacks {
        fun sleepNow()
        fun reloadWebView()
        fun exitApp()
        fun openControlCenter()
        fun getVolume(): Int
        fun setVolume(level: Int)
        fun isMuted(): Boolean
        fun toggleMute(): Boolean
        fun isMicMuted(): Boolean
        fun toggleMicMute()
        fun getBrightness(): Int
        fun setBrightness(level: Int)
        fun canWriteBrightnessSettings(): Boolean
        fun requestBrightnessPermission()
        fun isDarkMode(): Boolean
        fun toggleDarkMode()
        fun canWriteSecureSettings(): Boolean
        fun showDarkModePermissionDialog()
    }

    interface VideoFeedControlCallbacks {
        fun isVideoFeedsEnabled(): Boolean
        fun areVideoFeedsPaused(): Boolean
        fun togglePauseVideoFeeds()
        fun areVideoFeedAlertsMuted(): Boolean
        fun toggleMuteVideoFeedAlerts()
        fun cycleVideoFeedLayout()
        fun getVideoFeedLayout(): String
        fun getVideoFeedList(): List<CameraPopout.FeedInfo>
        fun refreshVideoFeedAvailability(onComplete: () -> Unit)
        fun showVideoFeed(ruleId: String)
        fun dismissVideoFeed(ruleId: String)
        fun setVideoFeedMenuOpen(open: Boolean)
        fun toggleVideoFeedStrip()
    }

    interface MusicNavigationCallbacks {
        fun isMusicEnabled(): Boolean
        fun onToggleMusicPlayer()
        fun switchDashboardView(viewId: String)

        /**
         * Whether the Home Assistant integration is enabled but missing
         * a stored access token (user enabled HA but never finished
         * login, or got logged out). Drives the amber warning triangle
         * on the HA sidebar icon and the click-to-prompt flow.
         */
        fun isHaNeedsLogin(): Boolean = false

        /**
         * Triggered when the user taps the HA sidebar icon while it's
         * showing the warning triangle. Should display a confirm dialog
         * and, on accept, launch the existing HA login flow (Fire tablet
         * → HaLoginActivity, others → inline OAuth in WebView).
         */
        fun onHaLoginRequested() {}
    }

    // Composed master interface — backward compatible
    interface Callbacks :
        LockCallbacks,
        StateCallbacks,
        DeviceControlCallbacks,
        VideoFeedControlCallbacks,
        MusicNavigationCallbacks

    // ── Views ────────────────────────────────────────────────────────

    private var stripView: SidebarStripView? = null
    private var popoutManager: SidebarPopoutManager? = null

    /**
     * Notified whenever the active dashboard view changes. Used by the
     * portrait bottom bar to keep its own active-indicator strip in
     * sync with the sidebar's — both surfaces reflect the same active
     * view, but only one is visible at a time per orientation.
     */
    var onActiveViewChanged: ((String?) -> Unit)? = null

    /** Expose strip root for visibility gate registration. */
    fun getStripRoot(): View? = stripView?.stripRoot

    // ── Popout handlers ──────────────────────────────────────────────

    private var hamburgerPopout: HamburgerPopout? = null
    private var volumePopout: VolumePopout? = null
    private var brightnessPopout: BrightnessPopout? = null
    private var cameraPopout: CameraPopout? = null

    // ── State ────────────────────────────────────────────────────────

    private var isLocked = false
    private var isPinned = true
    private var isEnabled = true
    // Suppressed by the visibility gate when the user isn't authenticated and
    // HA isn't configured (typical login / sign-up screen state). Forces the
    // sidebar fully hidden including the WebView leftMargin shift, so the
    // login screen isn't pushed right behind a pinned strip.
    //
    // D.61 — default `true` so the sidebar stays hidden during boot until
    // MainActivity calls setGateSuppressed(false) with the gate's authoritative
    // decision (typically after JS bootstrap fires setUiMode('full'/'kiosk')).
    // Previously defaulted to false, so initialize() → applySidebarVisibility()
    // would briefly show the strip before the gate flipped it back hidden.
    // That flash on every cold start is what D.61 reports.
    private var gateSuppressed = true
    // True when the FULL Dashie web app is loaded (visibility gate mode ==
    // FULL). In that mode the JS layout canvas owns the sidebar offset, so the
    // Kotlin WebView leftMargin must stay 0 to avoid a double shift. In KIOSK
    // mode (HA shell — no JS layout canvas) Kotlin applies the leftMargin.
    // Previously this was derived from AccountPreferences.isLinked, which is
    // wrong for a linked account whose web session expired and "Close Sign-in"
    // dropped it onto the HA shell: isLinked stays true but the shell needs
    // the Kotlin shift. Sourced from the gate's runtime mode instead.
    private var jsLayoutOwnsOffset = false
    private var isTV = false
    private var isDarkMode = false
    // Authoritative sidebar light/dark, set from the webapp's theme push
    // (setThemeColors) or an explicit dark toggle (refreshTheme). The whole
    // sidebar — panel bg, backdrop, AND icon tint — is painted from this one
    // value so they can't disagree. callbacks.isDarkMode() (the Kotlin
    // SharedPreferences pref) is only a cold-start fallback used before the
    // webapp has pushed a palette; it can be stale / first-launch-default,
    // which on slow low-RAM TVs produced dark-icons-on-a-dark-panel when the
    // icon tint (from the pref) and the panel (from the palette) raced.
    private var themedIsDark: Boolean? = null
    // Last palette JSON applied by setThemeColors. The webapp re-pushes the
    // current palette on authoritative-resolved / dashboard-ready /
    // webview-recreated (Fix D1/D2) to converge slow/recreate paths; an
    // unchanged palette arrives byte-identical (stable JSON.stringify key
    // order), so we skip the re-parse + re-paint to avoid needless flicker.
    // Cleared by refreshTheme()/resetThemeToNeutral() so the next push always
    // re-applies after an out-of-band tint change or sign-out neutralize.
    private var lastAppliedThemeColorsJson: String? = null
    private fun effectiveIsDark(): Boolean = themedIsDark ?: callbacks.isDarkMode()
    // Perceived-luminance dark check (ITU-R BT.601). Fallback for older JS that
    // doesn't send an explicit isDark — derives the icon tint from the panel
    // background so the icons still contrast with their panel.
    private fun isColorDark(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140.0
    }
    private var activeViewId: String? = null
    // Backdrop strip color: brand (steady state) vs theme-neutral (during
    // page reloads / fullscreen-mode holds — see setBackdropNeutral).
    private var backdropNeutral = false
    // Color for the sidebarBackgroundStrip View (the wider strip painted BEHIND
    // the icon panel). Sourced from --grid-gap-color so it matches the gap
    // area between widgets, while the panel itself uses --bg-primary. Null
    // until the webapp pushes a palette.
    private var themedStripBackdrop: Int? = null
    // Whether FullscreenModeManager is currently suppressing the sidebar
    // (icons hidden, backdrop neutralized, layout offset preserved).
    private var visuallySuppressed = false
    private var enabledViewIds: List<String> = emptyList()

    // True when the portrait bottom bar is the active chrome (portrait +
    // widgets dashboard). Set by PortraitLayoutManager from OrientationController
    // (the intended orientation — see applySidebarVisibility for why we can't
    // use Configuration.orientation on a TV). When true, the sidebar stays
    // hidden and d-pad focus is routed to the bottom bar instead.
    var portraitWidgetsActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applySidebarVisibility()
        }

    var isVisible: Boolean = false
        private set

    // ── Coroutines ───────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lockPollJob: Job? = null
    private var autoHideJob: Job? = null
    private var highlightTimeoutJob: Job? = null

    // ── Initialization ───────────────────────────────────────────────

    fun initialize() {
        val root = activity.findViewById<FrameLayout>(R.id.sidebarStripRoot)
            ?: run { Log.w(TAG, "sidebar_strip layout not found"); return }

        stripView = SidebarStripView(root.parent as FrameLayout)
        popoutManager = SidebarPopoutManager(activity)

        // Detect TV mode
        val uiMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        isTV = uiMode == Configuration.UI_MODE_TYPE_TELEVISION

        // Create popout handlers
        hamburgerPopout = HamburgerPopout { action -> handleHamburgerAction(action) }
        volumePopout = VolumePopout(
            onVolumeChanged = { callbacks.setVolume(it) },
            onToggleMute = { callbacks.toggleMute(); updateButtonVisibility(); resetAutoHide() },
            onToggleMicMute = { callbacks.toggleMicMute(); resetAutoHide() },
            onToggleAlertsMute = { callbacks.toggleMuteVideoFeedAlerts(); resetAutoHide() },
            onInteraction = { resetAutoHide() }
        )
        brightnessPopout = BrightnessPopout(
            onBrightnessChanged = { callbacks.setBrightness(it) },
            canWriteBrightnessSettings = { callbacks.canWriteBrightnessSettings() },
            onBrightnessPermissionNeeded = { showBrightnessPermissionDialog() },
            onToggleDarkMode = {
                callbacks.toggleDarkMode()
                // Refresh sidebar theme to match new dark mode state
                isDarkMode = effectiveIsDark()
                stripView?.applyTheme(isDarkMode)
                stripView?.setPinnedBackground(isPinned, isDarkMode)
                // Re-theme the active popout content
                popoutManager?.activeContentView?.let { applyPopoutTheme(it) }
                resetAutoHide()
                true // always succeeds now (SharedPrefs + resources.updateConfiguration)
            },
            onInteraction = { resetAutoHide() }
        )
        cameraPopout = CameraPopout(
            onCycleLayout = { callbacks.cycleVideoFeedLayout() },
            onTogglePause = { callbacks.togglePauseVideoFeeds() },
            onToggleMuteAlerts = { callbacks.toggleMuteVideoFeedAlerts() },
            onShowFeed = { callbacks.showVideoFeed(it) },
            onDismissFeed = { callbacks.dismissVideoFeed(it) },
            onInteraction = { resetAutoHide() }
        )

        // Wire strip button clicks
        wireButtonClicks()

        // Initial state
        isLocked = callbacks.isAppLocked()
        isPinned = callbacks.isDashBarPinned()
        isEnabled = callbacks.isSidebarEnabled()
        isDarkMode = effectiveIsDark()

        // Force-pin the sidebar for users without HA + Advanced Tablet
        // Controls. The pin/unpin toggle is hidden in the hamburger popout
        // for them (HamburgerPopout.kt), so an unpinned-from-prior-state
        // sidebar would have no recovery path. Mirrors the popout's
        // `advancedItemsVisible` gate — including its custom-URL-kiosk
        // carve-out: a "host your own dashboard" device defaults to
        // UNPINNED (a rail beside someone else's website is dead space),
        // and its popout does show Pin/Unpin, so unpinned is recoverable.
        val conn = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(activity)
        if (!conn.haEnabled && !conn.advancedTabletControlsEnabled
            && !conn.isCustomUrlKiosk && !isPinned) {
            Log.i(TAG, "Forcing sidebar pinned (non-HA, no advanced controls)")
            isPinned = true
            callbacks.setDashBarPinned(true)
        }

        // Apply theme and icon size (device boost × user setting)
        stripView?.applyTheme(isDarkMode)
        stripView?.applyIconSize(SidebarScaling.effectiveMultiplier(activity))
        // Scale strip width by icon boost, but apply a SEPARATE tighter boost
        // for vertical spacing so the absolute air around each icon actually
        // decreases on smaller devices (not just proportionally).
        stripView?.applyStripWidth(SidebarScaling.computeDeviceBoost(activity))
        stripView?.applyButtonSpacing(SidebarScaling.computeSpacingBoost(activity))

        // Apply initial visibility
        applySidebarVisibility()

        // Start lock polling
        startLockPolling()

        Log.i(TAG, "Initialized (TV=$isTV, locked=$isLocked, pinned=$isPinned, enabled=$isEnabled)")
    }

    // ── Strip button wiring ──────────────────────────────────────────

    private fun wireButtonClicks() {
        val strip = stripView ?: return

        strip.btnMusic.setOnClickListener {
            resetAutoHide()
            callbacks.onToggleMusicPlayer()
        }

        strip.btnCamera.setOnClickListener {
            resetAutoHide()
            callbacks.toggleVideoFeedStrip()
        }

        strip.btnVolume.setOnClickListener {
            resetAutoHide()
            showVolumePopout()
        }

        strip.btnBrightness.setOnClickListener {
            resetAutoHide()
            showBrightnessPopout()
        }

        strip.btnHamburger.setOnClickListener {
            resetAutoHide()
            showHamburgerPopout()
        }

        // View switcher button clicks
        val viewButtons = mapOf(
            "home" to strip.btnViewHomeAssistant,
            "calendar" to strip.btnViewCalendar,
            "chores" to strip.btnViewChores,
            "rewards" to strip.btnViewRewards,
            "location" to strip.btnViewLocation
        )
        viewButtons.forEach { (viewId, btn) ->
            btn.setOnClickListener {
                resetAutoHide()
                // HA-specific override: if the icon is currently showing
                // the "needs login" badge, fire the login prompt instead
                // of switching to the (broken) HA view. Stripe's
                // haNeedsLogin reads the badge visibility, which we sync
                // from prefs in refreshHaWarning().
                if (viewId == "home" && stripView?.haNeedsLogin == true) {
                    callbacks.onHaLoginRequested()
                    return@setOnClickListener
                }
                if (viewId != activeViewId) {
                    activeViewId = viewId
                    stripView?.setActiveViewButton(activeViewId)
                    onActiveViewChanged?.invoke(activeViewId)
                    callbacks.switchDashboardView(viewId)
                }
            }
        }

        // Backdrop click dismisses overlay sidebar
        strip.backdrop.setOnClickListener { dismissOverlay() }
    }

    // ── Sidebar visibility logic ─────────────────────────────────────

    /**
     * Determine effective visibility and mode based on enabled/locked/pinned state.
     * Matches JS setSidebarConfig() logic.
     */
    /**
     * Re-apply sidebar visibility based on current enabled/locked/pinned state.
     * Called internally and after WebView page loads to keep native sidebar in sync.
     */
    /**
     * Re-read pin state from prefs and apply visibility. Called from
     * MainBroadcastManager.ACTION_REFRESH_SIDEBAR_PIN when JS commits a
     * non-HA sign-in path (setHaEnabled(false)) — without this, the
     * post-OAuth force-pin would only take effect on next app start.
     */
    /**
     * Re-evaluate the HA "needs login" state and toggle the warning
     * triangle on the HA sidebar icon. Read from the callbacks
     * (which delegate to prefs) so this controller stays decoupled
     * from HalitePreferences directly.
     */
    fun refreshHaWarning() {
        stripView?.setHaNeedsLogin(callbacks.isHaNeedsLogin())
    }

    fun refreshPinFromPrefs(pinned: Boolean) {
        if (isPinned == pinned) {
            applySidebarVisibility()
            return
        }
        isPinned = pinned
        // Also re-read enabled state in case it changed (e.g. account
        // becoming linked flips isSidebarEnabled to true).
        isEnabled = callbacks.isSidebarEnabled()
        applySidebarVisibility()
    }

    fun applySidebarVisibility() {
        val strip = stripView ?: return

        // Re-evaluate the HA "needs login" badge. Cheap: just reads two
        // pref values. Doing it here means every visibility transition
        // (auth state, pin toggle, view switch) keeps the badge in sync.
        refreshHaWarning()

        // PORTRAIT WIDGETS — bottom bar replaces the sidebar entirely.
        // Every visibility transition routes through this method (gate
        // change, CC close, view switch, pin toggle), so the check
        // here is the chokepoint that prevents the sidebar from
        // popping back in. Without this, closing Control Center
        // re-triggered showPinned()/showOverlay() and the strip
        // briefly reappeared alongside the bottom bar. Gated on
        // portraitWidgetsActive (not bare orientation) so kiosk +
        // single-panel keep their normal side sidebar in portrait.
        // NOTE: this flag is set by PortraitLayoutManager from
        // OrientationController (the *intended* orientation). We can't use
        // Configuration.orientation here — a TV ignores the portrait
        // requestedOrientation and stays physically landscape, so that
        // signal reports landscape and the sidebar would never hide.
        if (portraitWidgetsActive) {
            hide()
            return
        }

        // Auth gate trumps everything — user isn't authenticated and HA isn't
        // configured, so the sidebar must be fully hidden (including the
        // WebView leftMargin shift in non-Dashie modes). Bypasses pinned /
        // overlay logic since none of those should apply pre-auth.
        if (gateSuppressed) {
            hide()
            return
        }

        val effectiveEnabled = isEnabled || isLocked

        if (!effectiveEnabled) {
            // Completely hidden
            hide()
            return
        }

        if (isPinned) {
            // Always visible, solid background
            showPinned()
        } else if (isLocked) {
            // Show as overlay so user can access unlock
            showOverlay()
        } else {
            // Enabled but unpinned: hidden until swipe reveals
            hide()
        }
    }

    private fun showPinned() {
        if (gateSuppressed) {
            Log.d(TAG, "showPinned ignored — gateSuppressed")
            return
        }
        val strip = stripView ?: return
        isDarkMode = effectiveIsDark()
        strip.stripRoot.visibility = View.VISIBLE
        strip.setPinnedBackground(true, isDarkMode)
        strip.applyTheme(isDarkMode)
        strip.hideBackdrop()
        updateButtonVisibility()
        isVisible = true
        autoHideJob?.cancel()
        notifyJsOfSidebarLayoutChange()
        updateRootBackground(true)
    }

    fun showOverlay() {
        if (gateSuppressed) {
            Log.d(TAG, "showOverlay ignored — gateSuppressed")
            return
        }
        val strip = stripView ?: return
        isDarkMode = effectiveIsDark()
        strip.stripRoot.visibility = View.VISIBLE
        strip.setPinnedBackground(false, isDarkMode)
        strip.applyTheme(isDarkMode)
        strip.showBackdrop()
        updateButtonVisibility()
        isVisible = true
        startAutoHide()
    }

    /**
     * Public method to reveal sidebar as overlay (e.g., from edge swipe gesture).
     */
    fun revealSidebar() {
        if (isVisible) return
        showOverlay()
    }

    /**
     * Cancel the auto-hide timer so the sidebar stays visible (e.g., during onboarding tips).
     */
    fun stopAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = null
    }

    /**
     * Dismiss the sidebar (used by onboarding tip cleanup).
     */
    fun dismiss() {
        popoutManager?.dismissPopout()
        val strip = stripView ?: return
        strip.stripRoot.visibility = View.GONE
        strip.hideBackdrop()
        isVisible = false
        autoHideJob?.cancel()
        cancelHighlightTimeout()
    }

    /**
     * Return the screen Y center of the hamburger button, or -1 if not available.
     * Used by onboarding tips to position the tip card next to the button.
     */
    fun getHamburgerButtonScreenY(): Int {
        val strip = stripView ?: return -1
        val btn = strip.btnHamburger
        val loc = IntArray(2)
        btn.getLocationOnScreen(loc)
        return loc[1] + btn.height / 2
    }

    /**
     * Reveal the sidebar for onboarding tips — NO backdrop and NO auto-hide.
     * The backdrop is a native view that would cover the WebView tip card,
     * so we show the strip without dimming the underlying content.
     */
    fun revealForOnboardingTip() {
        val strip = stripView ?: return
        isDarkMode = effectiveIsDark()
        strip.stripRoot.visibility = View.VISIBLE
        strip.setPinnedBackground(false, isDarkMode)
        strip.applyTheme(isDarkMode)
        strip.hideBackdrop()
        updateButtonVisibility()
        isVisible = true
        autoHideJob?.cancel()
        autoHideJob = null
    }

    /**
     * Reveal sidebar + open hamburger popout for the control center onboarding tip.
     * No backdrop so the WebView tip card is visible to the right of the popout.
     */
    fun revealWithHamburgerPopoutForTip() {
        revealForOnboardingTip()
        val strip = stripView ?: return
        val contentView = popoutManager?.togglePopout(
            com.dashieapp.Dashie.R.layout.popout_hamburger,
            strip.btnHamburger,
            strip.panel,
            "hamburger",
            alignment = SidebarPopoutManager.Alignment.BOTTOM,
            showBackdrop = false
        ) ?: return
        applyPopoutTheme(contentView)
        applyPopoutScale(contentView)
        val layoutMode = com.dashieapp.Dashie.halite.preferences.DisplayPreferences(activity).layoutMode
        hamburgerPopout?.bind(contentView, isLocked, isPinned, layoutMode)
        contentView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contentView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                popoutManager?.repositionActivePopout()
            }
        })
    }

    /**
     * Return the screen bounds of the hamburger popout's Control Center item as JSON.
     * Format: {"popoutRight": X, "controlCenterY": Y}
     * Returns "{}" if the popout is not open or the item is not found.
     * Call this after openHamburgerPopout() with a short delay to allow layout.
     */
    fun getControlCenterItemBounds(): String {
        val contentView = popoutManager?.activeContentView ?: return "{}"
        val ccItem = contentView.findViewById<View>(com.dashieapp.Dashie.R.id.popoutItemControlCenter)
            ?: return "{}"
        val contentLoc = IntArray(2)
        val itemLoc = IntArray(2)
        contentView.getLocationOnScreen(contentLoc)
        ccItem.getLocationOnScreen(itemLoc)
        val popoutRight = contentLoc[0] + contentView.width
        val ccCenterY = itemLoc[1] + ccItem.height / 2
        return """{"popoutRight":$popoutRight,"controlCenterY":$ccCenterY}"""
    }

    fun hide(keepWebViewMargin: Boolean = false) {
        val strip = stripView ?: return
        popoutManager?.dismissPopout()
        strip.stripRoot.visibility = View.GONE
        strip.hideBackdrop()
        isVisible = false
        autoHideJob?.cancel()
        cancelHighlightTimeout()
        if (!keepWebViewMargin) {
            // Sidebar no longer visible → JS drops the layout offset.
            notifyJsOfSidebarLayoutChange()
            updateRootBackground(false)
        }
    }

    fun show() {
        if (isPinned) showPinned() else showOverlay()
    }

    private fun dismissOverlay(focusFirstSlot: Boolean = false) {
        popoutManager?.dismissPopout()
        if (!isPinned) {
            hide()
            callbacks.onSidebarDismissed()
            // D.44 / D.47 — tell the JS dashboard either to clear focus
            // (BACK from sidebar) or to move focus to the first dashboard
            // slot (RIGHT from sidebar). The pinned-sidebar path uses
            // unfocusSidebar() for the same purpose.
            val webView = activity.findViewById<android.webkit.WebView>(R.id.dashboardWebView)
            val jsFn = if (focusFirstSlot)
                "LayoutCanvasInputHandler_focusFirstSlotFromSidebar"
            else
                "LayoutCanvasInputHandler_onReturnFromSidebar"
            webView?.evaluateJavascript(
                "if (typeof window.$jsFn === 'function') window.$jsFn();",
                null
            )
        }
    }

    // ── JS layout notification ────────────────────────────────────
    // The WebView stays match_parent. JS reads the native sidebar
    // width + pinned state via DashieJSBridge.getDashBarWidthPx() /
    // isDashBarPinned() and offsets .layout-canvas via a CSS var
    // (see js/core/native-sidebar-layout.js). No Android-level
    // scaleX transform — avoids the WebView-surface-caching bugs
    // where the transform is ignored after page reloads.

    /**
     * Kick the JS layout to re-evaluate the sidebar offset. Called
     * when sidebar visibility / pin state / icon size changes so the
     * JS-side CSS var updates without waiting for the next full page
     * load.
     *
     * Also adjusts WebView leftMargin in **non-Dashie modes** (kiosk /
     * HA-only). In those modes the JS layout canvas isn't running, so
     * applyNativeSidebarLayout() is a no-op — we shift the entire
     * WebView at the Android layout layer instead so HA / kiosk
     * content moves out from behind the sidebar. In Dashie/widgets
     * mode the WebView stays full-width and JS handles the offset.
     */
    private fun notifyJsOfSidebarLayoutChange() {
        val webView = activity.findViewById<android.webkit.WebView>(R.id.dashboardWebView) ?: return
        webView.post {
            // 1) JS path (widgets mode only — no-op everywhere else)
            webView.evaluateJavascript(
                // BUNDLE-EXEMPT: applyNativeSidebarLayout — pushes sidebar layout into the full-mode dashboard; kiosk has no dashie layout
                "if (typeof window.applyNativeSidebarLayout === 'function') " +
                        "window.applyNativeSidebarLayout();",
                null
            )
            // 2) Kotlin layout-margin path for non-Dashie modes
            applyWebViewLeftMarginForKioskMode(webView)
        }
    }

    /**
     * In kiosk / HA-only modes (no Dashie JS layout canvas), shift the
     * WebView to the right of the sidebar by setting leftMargin. In
     * Dashie/widgets mode this clears the margin (JS handles the offset
     * via CSS var, and a Kotlin margin would double-shift).
     */
    private fun applyWebViewLeftMarginForKioskMode(webView: android.view.View) {
        val lp = webView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        // No left shift if the sidebar is hidden — pinned alone isn't enough,
        // since the auth gate (or `enabled=false`) can leave the strip GONE
        // while isPinned remains true. Drove the bug where the login screen
        // was shifted right behind an invisible sidebar.
        val sidebarOccupiesSpace = isPinned && !gateSuppressed && (isEnabled || isLocked)
        // jsLayoutOwnsOffset → FULL Dashie app is loaded and its JS canvas
        // shifts the dashboard itself; a Kotlin margin would double-shift.
        val targetMargin = if (!jsLayoutOwnsOffset && sidebarOccupiesSpace) {
            val effectiveWidthDp = SIDEBAR_WIDTH_DP * SidebarScaling.computeDeviceBoost(activity)
            (effectiveWidthDp * activity.resources.displayMetrics.density).toInt()
        } else 0
        if (lp.leftMargin == targetMargin) return
        lp.leftMargin = targetMargin
        webView.layoutParams = lp
        Log.i(TAG, "WebView leftMargin → $targetMargin (jsLayoutOwnsOffset=$jsLayoutOwnsOffset, pinned=$isPinned)")
    }

    // ── Button visibility updates ────────────────────────────────────

    private fun updateButtonVisibility() {
        stripView?.setMusicVisible(callbacks.isMusicEnabled())
        stripView?.setCameraVisible(callbacks.isVideoFeedsEnabled())
        stripView?.updateVolumeIcon(callbacks.isMuted())
    }

    // ── Lock state polling ───────────────────────────────────────────

    private fun startLockPolling() {
        lockPollJob?.cancel()
        lockPollJob = scope.launch {
            while (isActive) {
                delay(LOCK_POLL_MS)
                val newLocked = callbacks.isAppLocked()
                if (newLocked != isLocked) {
                    isLocked = newLocked
                    Log.i(TAG, "Lock state changed: $isLocked")
                    onLockStateChanged()
                }
                // Also refresh button visibility (cheap check, picks up music/camera changes)
                if (isVisible) updateButtonVisibility()
            }
        }
    }

    private fun onLockStateChanged() {
        popoutManager?.dismissPopout()
        applySidebarVisibility()
    }

    // ── Auto-hide timer ──────────────────────────────────────────────

    private fun startAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            dismissOverlay()
        }
    }

    private fun resetAutoHide() {
        if (isVisible) {
            startAutoHide()
        }
    }

    // ── Highlight idle timeout ───────────────────────────────────────
    // The sidebar "highlight" is just a button holding Android focus. Mirror
    // the widget focus ring: after a period of d-pad inactivity, fade the
    // highlight by handing focus back to the dashboard. Restarted on every
    // sidebar navigation; cancelled when focus leaves the strip.

    private fun startHighlightTimeout() {
        highlightTimeoutJob?.cancel()
        highlightTimeoutJob = scope.launch {
            delay(HIGHLIGHT_TIMEOUT_MS)
            // Only clear if a button is still highlighted and no popout is open
            // (popouts own their own auto-hide and refocus the strip on close).
            if (hasFocus && popoutManager?.isPopoutVisible != true) {
                Log.d(TAG, "Sidebar highlight idle timeout — clearing focus")
                if (isPinned) unfocusSidebar() else dismissOverlay()
            }
        }
    }

    private fun cancelHighlightTimeout() {
        highlightTimeoutJob?.cancel()
        highlightTimeoutJob = null
    }

    // ── Popout theme ─────────────────────────────────────────────────

    /**
     * Apply theme-aware background and text colors to a popout content view.
     * Walk all TextViews and set color; set root background drawable.
     *
     * Prefers values from [com.dashieapp.Dashie.halite.theming.NativeThemeManager]
     * (populated by the webapp on each theme change). Falls back to the original
     * dark/light drawables and hardcoded text colors when the manager hasn't been
     * populated yet (cold boot before webapp render).
     */
    private fun applyPopoutTheme(contentView: android.view.View) {
        val tm = com.dashieapp.Dashie.halite.theming.NativeThemeManager
        val themedBg = tm.getBgPrimary()
        val themedText = tm.getTextPrimary()
        val themedTextSecondary = tm.getTextSecondary()
        val themedBorder = tm.getBgTertiary()

        if (themedBg != null) {
            // Build the popout background programmatically so we can apply themed
            // colors. Matches bg_sidebar_popout.xml shape: 12dp rounded corners
            // with a 0.5dp border (which we colorize from --bg-tertiary when
            // available, else inherit a sensible default).
            val density = contentView.resources.displayMetrics.density
            contentView.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(themedBg)
                if (themedBorder != null) {
                    setStroke((0.5f * density).toInt(), themedBorder)
                }
            }
        } else {
            // No webapp palette yet — fall back to the static drawables that pick
            // up Android's day/night resource qualifier.
            val bgRes = if (isDarkMode) R.drawable.bg_sidebar_popout else R.drawable.bg_sidebar_popout_light
            contentView.setBackgroundResource(bgRes)
        }

        // 🔴 THE MARK FOLLOWS THE SAME THEME DECISION AS THE SURFACE IT SITS ON.
        //
        // 2026-08-04, looking at the Fire: the popout showed the bare Chickadee ICON, not
        // the wordmark lockup. Two separate defects behind one symptom, and the second is this:
        // the popout is themed at RUNTIME (the background a few lines above is chosen by
        // `isDarkMode`, not by a `-night` qualifier), so a single mark cannot serve it. Chickadee's
        // light-ink wordmark measures ~1.55:1 on the light popout — the same near-invisible mark
        // as s55, arrived at from the other direction.
        //
        // Per-theme marks as DATA, not a branch on edition: each source set declares both names,
        // and Dashie's two are deliberately the SAME file ("the orange on white is really
        // fine" — one mark on both surfaces, permanently). That mirrors `brand.js`'s
        // `sidebarLogo: {dark, light}`, because it is the same decision one slot over.
        //
        // ⚠️ `dashie_logo_orange` is a LEGACY NAME whose CONTENT is replaced per edition — the
        // documented drawable strategy. Do not read it as "the orange Dashie mark" here.
        contentView.findViewById<android.widget.ImageView>(R.id.popoutLogoImg)
            ?.setImageResource(
                if (isDarkMode) R.drawable.dashie_logo_orange
                else R.drawable.brand_popout_mark_light)

        val textColor = themedText
            ?: if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()
        val secondaryTextColor = themedTextSecondary
            ?: if (isDarkMode) 0xFF8E8E93.toInt() else 0xFF757575.toInt()

        applyThemeToViewTree(contentView, textColor, secondaryTextColor)

        // Re-theme popup menu item highlights — bg_popout_item hardcodes an
        // orange (#33FF9500) focused state that doesn't track the theme. Walk
        // the tree and replace matching backgrounds with a themed selector
        // built from accentSecondary.
        val themedAccent = com.dashieapp.Dashie.halite.theming.NativeThemeManager.getAccentSecondary()
        if (themedAccent != null) {
            applyPopoutItemHighlight(contentView, themedAccent)
        }
    }

    /** Walk the popout tree and replace the focused-state highlight on every
     *  popout menu item with a themed RippleDrawable.
     *
     *  Uses a focusable+clickable heuristic instead of constantState comparison
     *  — RippleDrawable wrapping breaks constantState equality for XML-inflated
     *  bg_popout_item drawables (same trap as the sidebar buttons). Popout
     *  items are uniquely identified by being focusable AND clickable container
     *  views (FrameLayout / LinearLayout), and that's exactly the surface we
     *  want themed regardless of which specific drawable each layout used. */
    private fun applyPopoutItemHighlight(root: android.view.View, accent: Int) {
        themeFocusableContainersRecursive(root, accent)
    }

    private fun themeFocusableContainersRecursive(view: android.view.View, accent: Int) {
        // Skip the popout root itself; only swap on focusable container children.
        val isItemContainer = view.isFocusable && view.isClickable &&
            view !is android.widget.ImageView && view !is android.widget.TextView
        if (isItemContainer && view.background != null) {
            view.background = makeThemedPopoutItemBg(accent)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                themeFocusableContainersRecursive(view.getChildAt(i), accent)
            }
        }
    }

    /** Build a themed equivalent of bg_popout_item: transparent default, focused
     *  state shows the accent at ~20% alpha, ripple on touch. Matches the
     *  original drawable's visual semantics but with theme colors. */
    private fun makeThemedPopoutItemBg(accent: Int): android.graphics.drawable.RippleDrawable {
        val focusedFill = (accent and 0x00FFFFFF) or (0x33 shl 24)  // ~20% alpha
        val rippleAlpha = (accent and 0x00FFFFFF) or (0x20 shl 24)
        val selector = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(focusedFill)
            })
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleAlpha),
            selector,
            null
        )
    }

    private fun applyThemeToViewTree(view: android.view.View, textColor: Int, secondaryColor: Int) {
        if (view is android.widget.TextView) {
            // Skip step buttons (-/+) that use orange circle bg — they must stay white
            val isStepBtn = view.id == R.id.volumeBtnMinus || view.id == R.id.volumeBtnPlus
                    || view.id == R.id.brightnessBtnMinus || view.id == R.id.brightnessBtnPlus
            // Skip dynamically created feed toggle buttons and active feed labels (tagged)
            val tag = view.tag as? String
            val isFeedToggle = tag != null && tag.startsWith("feedToggle_")
            val isFeedLabel = tag != null && tag.startsWith("feedLabel_")
            if (!isStepBtn && !isFeedToggle && !isFeedLabel) {
                // Small text (12-13sp) gets secondary color, others get primary
                val textSize = view.textSize / view.resources.displayMetrics.scaledDensity
                if (textSize <= 13f) {
                    view.setTextColor(secondaryColor)
                } else {
                    view.setTextColor(textColor)
                }
            }
        }
        if (view is android.widget.ImageView) {
            // Skip tinting the Dashie logo (it should keep its original orange color)
            if (view.id != R.id.popoutLogoImg) {
                view.colorFilter = android.graphics.PorterDuffColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeToViewTree(view.getChildAt(i), textColor, secondaryColor)
            }
        }
    }

    // ── Popout display ───────────────────────────────────────────────

    /**
     * Whether the host activity is currently in portrait orientation.
     * Used to switch popout anchors between the landscape sidebar and
     * the portrait bottom bar (bottom_bar.xml) — both wire their clicks
     * through the same NativeSidebarController showXxx methods, so we
     * pick the right view-tree refs at popout time.
     */
    private val isPortraitLayout: Boolean
        get() = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT

    /**
     * The portrait bottom-bar swap applies ONLY in the widgets dashboard
     * (logged-in, non-kiosk). Kiosk (HA shell) and single-panel keep their
     * normal side sidebar in both orientations.
     *
     * The canonical signal is `account.isLinked`: the widgets/single-panel
     * dashboard only loads when the user is signed into Dashie. A pure-kiosk
     * (HA-only) user has isLinked=false AND — per device logs 2026-05-29 —
     * forceKioskMode=false AND the default layoutMode="widgets" (they can't
     * reach the layout picker to change it). So neither layoutMode nor
     * forceKioskMode alone catches them; isLinked does. forceKioskMode is
     * kept as a belt-and-suspenders guard for a signed-in user who toggled
     * into kiosk (layoutMode would also be "kiosk" there).
     */
    // Use the authoritative flag set by PortraitLayoutManager (from
    // OrientationController). The old Configuration.orientation-based check
    // reported landscape on a TV (which ignores the portrait request), so
    // popout anchoring/positioning broke on TVs. portraitWidgetsActive already
    // folds in the same isLinked / !kiosk / widgets-mode conditions.
    private val isPortraitWidgetsMode: Boolean
        get() = portraitWidgetsActive

    /** In portrait, return the matching button from bottom_bar.xml.
     *  Returns null when the bottom bar isn't inflated (shouldn't
     *  happen in portrait, but defensive). */
    private fun bottomBarAnchor(@androidx.annotation.IdRes id: Int): View? =
        activity.findViewById(id)

    private fun bottomBarPanel(): View? = activity.findViewById(R.id.bottomBarPanel)

    private fun showHamburgerPopout() {
        val strip = stripView ?: return
        val portrait = isPortraitWidgetsMode
        val anchor = if (portrait) bottomBarAnchor(R.id.bottomBarBtnHamburger) ?: strip.btnHamburger
                     else strip.btnHamburger
        val panel = if (portrait) bottomBarPanel() ?: strip.panel else strip.panel
        val align = if (portrait) SidebarPopoutManager.Alignment.ABOVE_ANCHOR_START
                    else SidebarPopoutManager.Alignment.BOTTOM
        val contentView = popoutManager?.togglePopout(
            R.layout.popout_hamburger,
            anchor,
            panel,
            "hamburger",
            alignment = align
        ) ?: return

        applyPopoutTheme(contentView)
        applyPopoutScale(contentView)
        val layoutMode = com.dashieapp.Dashie.halite.preferences.DisplayPreferences(activity).layoutMode
        hamburgerPopout?.bind(contentView, isLocked, isPinned, layoutMode)
        // Reposition after bind — locked/unlocked sections change the popout height
        contentView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contentView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                popoutManager?.repositionActivePopout()
            }
        })
        // Pinned sidebar cancels the strip's auto-hide; popouts still need to
        // dismiss themselves when idle, so kick the timer here. dismissOverlay
        // tears down the popout and only hides the strip when !isPinned.
        startAutoHide()
    }

    private fun showVolumePopout() {
        val strip = stripView ?: return
        val layoutRes = if (isTV) R.layout.popout_volume_tv else R.layout.popout_volume
        val portrait = isPortraitWidgetsMode
        val anchor = if (portrait) bottomBarAnchor(R.id.bottomBarBtnVolume) ?: strip.btnVolume
                     else strip.btnVolume
        val panel = if (portrait) bottomBarPanel() ?: strip.panel else strip.panel
        val align = if (portrait) SidebarPopoutManager.Alignment.ABOVE_ANCHOR_CENTER
                    else SidebarPopoutManager.Alignment.CENTER_ON_ANCHOR
        val contentView = popoutManager?.togglePopout(
            layoutRes, anchor, panel, "volume",
            alignment = align
        ) ?: return

        applyPopoutTheme(contentView)
        applyPopoutScale(contentView)
        if (isTV) {
            volumePopout?.bindTV(contentView, callbacks.isMuted(), callbacks.isMicMuted())
        } else {
            volumePopout?.bindTablet(
                contentView,
                callbacks.getVolume(),
                callbacks.isMuted(),
                callbacks.isMicMuted(),
                callbacks.areVideoFeedAlertsMuted(),
            )
        }
        startAutoHide()
    }

    private fun showBrightnessPopout() {
        val strip = stripView ?: return

        // TV devices: toggle dark mode directly (no popout needed)
        if (isTV) {
            callbacks.toggleDarkMode()
            isDarkMode = effectiveIsDark()
            stripView?.applyTheme(isDarkMode)
            stripView?.setPinnedBackground(isPinned, isDarkMode)
            return
        }

        val portrait = isPortraitWidgetsMode
        val anchor = if (portrait) bottomBarAnchor(R.id.bottomBarBtnBrightness) ?: strip.btnBrightness
                     else strip.btnBrightness
        val panel = if (portrait) bottomBarPanel() ?: strip.panel else strip.panel
        val align = if (portrait) SidebarPopoutManager.Alignment.ABOVE_ANCHOR_CENTER
                    else SidebarPopoutManager.Alignment.CENTER_ON_ANCHOR
        val contentView = popoutManager?.togglePopout(
            R.layout.popout_brightness, anchor, panel, "brightness",
            alignment = align
        ) ?: return

        applyPopoutTheme(contentView)
        applyPopoutScale(contentView)
        brightnessPopout?.bindTablet(contentView, callbacks.getBrightness(), callbacks.isDarkMode())
        startAutoHide()
    }

    private fun showBrightnessPermissionDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Brightness Control"
        dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
            "To control brightness, ${activity.brandName()} needs the \"Modify system settings\" permission."
        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).text = "Open Settings"

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            callbacks.requestBrightnessPermission()
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    private fun showCameraPopout() {
        val strip = stripView ?: return
        val contentView = popoutManager?.togglePopout(
            R.layout.popout_camera, strip.btnCamera, strip.panel, "camera",
            alignment = SidebarPopoutManager.Alignment.CENTER_ON_ANCHOR,
            onDismiss = { callbacks.setVideoFeedMenuOpen(false) }
        ) ?: run {
            callbacks.setVideoFeedMenuOpen(false)
            return
        }

        callbacks.setVideoFeedMenuOpen(true)
        cameraPopout?.bind(
            contentView, activity,
            callbacks.areVideoFeedsPaused(),
            callbacks.areVideoFeedAlertsMuted(),
            callbacks.getVideoFeedLayout(),
            isDarkMode,
            callbacks.getVideoFeedList()
        )
        applyPopoutTheme(contentView)
        applyPopoutScale(contentView)

        // Refresh availability from HA in the background; rebind if the popout is still open
        callbacks.refreshVideoFeedAvailability {
            activity.runOnUiThread {
                if (contentView.isAttachedToWindow) {
                    cameraPopout?.bind(
                        contentView, activity,
                        callbacks.areVideoFeedsPaused(),
                        callbacks.areVideoFeedAlertsMuted(),
                        callbacks.getVideoFeedLayout(),
                        isDarkMode,
                        callbacks.getVideoFeedList()
                    )
                    popoutManager?.repositionActivePopout()
                }
            }
        }
        // Reposition after dynamic stream list has been laid out.
        // Use doOnLayout to ensure the added stream rows have been measured.
        contentView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contentView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                popoutManager?.repositionActivePopout()
                // Set initial d-pad focus after layout is complete
                val layoutBtn = contentView.findViewById<View>(R.id.cameraBtnLayout)
                layoutBtn?.requestFocus()
            }
        })
        // Camera popout intentionally does NOT auto-hide — user is often
        // monitoring a feed and we don't want the popout to vanish on them.
    }

    // ── Hamburger action routing ─────────────────────────────────────

    private fun handleHamburgerAction(action: HamburgerPopout.HamburgerAction) {
        popoutManager?.dismissPopout()

        when (action) {
            HamburgerPopout.HamburgerAction.PIN -> {
                isPinned = !isPinned
                callbacks.setDashBarPinned(isPinned)
                applySidebarVisibility()
            }
            HamburgerPopout.HamburgerAction.EDIT_LAYOUT -> {
                // Send broadcast to enter layout edit mode (handled by MainBroadcastManager)
                activity.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_EDIT_LAYOUT").apply {
                        setPackage(activity.packageName)
                    }
                )
            }
            HamburgerPopout.HamburgerAction.LOCK -> callbacks.showLockDialog()
            HamburgerPopout.HamburgerAction.SLEEP -> callbacks.sleepNow()
            HamburgerPopout.HamburgerAction.RELOAD -> callbacks.reloadWebView()
            HamburgerPopout.HamburgerAction.EXIT -> callbacks.exitApp()
            HamburgerPopout.HamburgerAction.CONTROL_CENTER -> callbacks.openControlCenter()
            HamburgerPopout.HamburgerAction.UNLOCK -> callbacks.showUnlockDialog()
            HamburgerPopout.HamburgerAction.PIN_RECOVERY -> callbacks.showPinRecoveryDialog()
            HamburgerPopout.HamburgerAction.CONNECT_MOBILE ->
                com.dashieapp.Dashie.edition.EditionSeams.paywall(activity).showConnectMobile()
        }
    }

    // ── D-pad navigation ─────────────────────────────────────────────

    /**
     * Whether any sidebar button currently has Android focus.
     * Used by dispatchKeyEvent to decide whether to route d-pad here or to JS/WebView.
     */
    val hasFocus: Boolean
        get() {
            val buttons = stripView?.getVisibleButtons() ?: return false
            return buttons.any { it.isFocused }
        }

    /**
     * Focus the sidebar strip (first visible button).
     * Called when DPAD_LEFT is pressed and the sidebar is visible but unfocused.
     * For unpinned/hidden sidebar, also reveals it first.
     */
    fun focusSidebar() {
        // Portrait widgets mode uses the bottom bar, not the sidebar. JS routes
        // the d-pad to DashieNative.focusBottomBar() directly there; guard here
        // so any stray focusSidebar() call can't pop the hidden sidebar back up.
        if (portraitWidgetsActive) return
        if (!isVisible) {
            showOverlay()
        }
        val buttons = stripView?.getVisibleButtons() ?: return
        if (buttons.isNotEmpty()) {
            buttons.last().requestFocus()  // Bottom button (hamburger) is most common target
            Log.d(TAG, "Sidebar focused via d-pad")
            startHighlightTimeout()
        }
    }

    /**
     * Remove focus from sidebar buttons, returning focus to the WebView/HA dashboard.
     *
     * @param focusFirstSlot when true, JS focuses the first dashboard slot
     *   instead of clearing focus. Used by the RIGHT-from-sidebar path so a
     *   single press lands on a widget (D.47). The default false matches the
     *   D.44 behavior — BACK from sidebar clears focus and the user's next
     *   d-pad press picks a slot.
     */
    fun unfocusSidebar(focusFirstSlot: Boolean = false) {
        cancelHighlightTimeout()
        val buttons = stripView?.getVisibleButtons() ?: return
        buttons.forEach { it.clearFocus() }
        // Return focus to WebView
        val webView = activity.findViewById<android.webkit.WebView>(R.id.dashboardWebView)
        webView?.requestFocus()
        val jsFn = if (focusFirstSlot)
            "LayoutCanvasInputHandler_focusFirstSlotFromSidebar"
        else
            "LayoutCanvasInputHandler_onReturnFromSidebar"
        webView?.evaluateJavascript(
            "if (typeof window.$jsFn === 'function') window.$jsFn();",
            null
        )
    }

    fun handleDpadKey(keyCode: Int): Boolean {
        if (!isVisible) return false

        // If a popout is showing, handle d-pad navigation within the popout.
        // Android's spatial focus search can't reliably find the popout views
        // since they're added to the decorView with margin-based positioning.
        if (popoutManager?.isPopoutVisible == true) {
            return handlePopoutDpad(keyCode)
        }

        // Only intercept d-pad when a sidebar button actually has focus.
        // This prevents the sidebar from stealing all d-pad events from the
        // HA dashboard when the strip is pinned but the user is navigating the dashboard.
        val buttons = stripView?.getVisibleButtons() ?: return false
        if (buttons.isEmpty()) return false

        val currentFocus = buttons.indexOfFirst { it.isFocused }
        if (currentFocus < 0) return false  // No button focused — let d-pad pass through

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val next = if (currentFocus <= 0) buttons.size - 1 else currentFocus - 1
                buttons[next].requestFocus()
                startHighlightTimeout()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = if (currentFocus >= buttons.size - 1) 0 else currentFocus + 1
                buttons[next].requestFocus()
                startHighlightTimeout()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Right from a left-side sidebar = move into widgets.
                // Sidebar is the leftmost column, dashboard is to the right.
                // Use ENTER/CENTER to activate the focused button (open popout / switch view).
                // D.47 — focusFirstSlot=true so the press lands on a widget
                // in one go; BACK still clears focus via the default path.
                if (isPinned) {
                    unfocusSidebar(focusFirstSlot = true)
                } else {
                    dismissOverlay(focusFirstSlot = true)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Left from leftmost column: for unpinned sidebar, dismiss the overlay.
                // For pinned sidebar, there's nowhere further left to go — no-op.
                if (!isPinned) {
                    dismissOverlay()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                buttons[currentFocus].performClick()
                // performClick may open a popout (focus moves there, its own
                // auto-hide takes over) or switch a view (focus stays on the
                // button). Restart either way; the fire guard skips while a
                // popout is up, and dismissPopoutAndRefocus restarts on close.
                startHighlightTimeout()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isPinned) {
                    unfocusSidebar()
                } else {
                    dismissOverlay()
                }
                true
            }
            else -> false
        }
    }

    /**
     * Handle d-pad navigation within a popout menu.
     * Manually walks focusable views since the popout is a decorView child
     * that Android's spatial focus search doesn't reliably traverse to.
     */
    private fun handlePopoutDpad(keyCode: Int): Boolean {
        val contentView = popoutManager?.activeContentView ?: return false

        // Collect all focusable views in the popout
        val focusables = mutableListOf<View>()
        collectFocusableViews(contentView, focusables)
        if (focusables.isEmpty()) return false

        val currentIdx = focusables.indexOfFirst { it.isFocused }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentIdx <= 0) {
                    focusables.last().requestFocus()
                } else {
                    focusables[currentIdx - 1].requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // RIGHT advances through items too (e.g. horizontal button rows)
                if (currentIdx < 0 || currentIdx >= focusables.size - 1) {
                    focusables.first().requestFocus()
                } else {
                    focusables[currentIdx + 1].requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (currentIdx >= 0) {
                    focusables[currentIdx].performClick()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> {
                dismissPopoutAndRefocus()
                true
            }
            else -> false
        }
    }

    /** Recursively collect visible, focusable views in tree order. */
    private fun collectFocusableViews(view: View, out: MutableList<View>) {
        if (view.visibility != View.VISIBLE) return
        if (view is android.view.ViewGroup) {
            if (view.isFocusable && view.isClickable) {
                // Interactive container (menu item row) — add as focusable unit.
                // Don't recurse into children; the container is the click target.
                out.add(view)
            } else {
                // Non-interactive container (ScrollView, wrapper LinearLayout) —
                // recurse into children even if focusable (ScrollView is focusable
                // by default for scroll-by-d-pad, but we want its children).
                for (i in 0 until view.childCount) {
                    collectFocusableViews(view.getChildAt(i), out)
                }
            }
        } else if (view.isFocusable) {
            // Leaf view (TextView, ImageView, etc.) that's focusable
            out.add(view)
        }
    }

    // ── Public API ───────────────────────────────────────────────────

    /** Whether a popout (PopupWindow) is currently showing. */
    val isPopoutShowing: Boolean get() = popoutManager?.isPopoutVisible == true

    /**
     * Dismiss the active popout and return focus to the sidebar strip button
     * that opened it.
     */
    fun dismissPopoutAndRefocus() {
        val popoutId = popoutManager?.currentPopoutId
        popoutManager?.dismissPopout()
        val strip = stripView ?: return
        // In portrait the sidebar strip is hidden, so refocusing strip.btnXxx
        // (a GONE view) is a no-op and drops d-pad focus entirely. Return focus
        // to the matching bottom-bar button instead. Its own highlight timeout
        // (re)starts on the next bottom-bar key.
        if (portraitWidgetsActive) {
            val barBtnId = when (popoutId) {
                "volume" -> R.id.bottomBarBtnVolume
                "brightness" -> R.id.bottomBarBtnBrightness
                "camera" -> R.id.bottomBarBtnCamera
                else -> R.id.bottomBarBtnHamburger
            }
            activity.findViewById<View>(barBtnId)?.requestFocus()
            return
        }
        // Return focus to the strip button that corresponds to the dismissed popout
        val targetBtn = when (popoutId) {
            "hamburger" -> strip.btnHamburger
            "volume" -> strip.btnVolume
            "brightness" -> strip.btnBrightness
            "camera" -> strip.btnCamera
            else -> strip.btnHamburger
        }
        targetBtn.requestFocus()
        startHighlightTimeout()
    }

    /**
     * Refresh button visibility (call when video feeds or music state changes).
     */
    fun refreshButtonVisibility() {
        if (isVisible) updateButtonVisibility()
    }

    /**
     * Update the set of enabled dashboard views (called from JS bridge on startup/settings change).
     * Shows view switcher buttons when 2+ views are enabled, hides when fewer.
     */
    fun setEnabledViews(viewIds: List<String>) {
        enabledViewIds = viewIds
        stripView?.setEnabledViewButtons(viewIds)
        // Default to first view if nothing is active yet, or active view was removed
        if (activeViewId == null || activeViewId !in viewIds) {
            activeViewId = viewIds.firstOrNull()
        }
        stripView?.setActiveViewButton(activeViewId)
        onActiveViewChanged?.invoke(activeViewId)
    }

    /**
     * Notify the sidebar that the active dashboard view changed (e.g., navigated via WebView).
     */
    fun setActiveView(viewId: String) {
        if (viewId != activeViewId) {
            activeViewId = viewId
            stripView?.setActiveViewButton(activeViewId)
            onActiveViewChanged?.invoke(activeViewId)
        }
    }

    /**
     * Update pin state from external change (e.g., JS bridge callback).
     */
    fun onPinChanged(pinned: Boolean) {
        isPinned = pinned
        applySidebarVisibility()
    }

    /**
     * Refresh sidebar icon tint and panel background to match a new dark mode state.
     * Called when dark mode changes from outside the sidebar (e.g., JS setDarkMode() bridge call).
     */
    fun refreshTheme(isDark: Boolean) {
        // Explicit dark toggle is authoritative too — record it as the sidebar's
        // source of truth so a later re-show (showPinned) keeps it.
        themedIsDark = isDark
        isDarkMode = isDark
        // An out-of-band tint change invalidates the no-op cache so the next
        // palette push re-applies (and re-derives the tint from the palette).
        lastAppliedThemeColorsJson = null
        stripView?.applyTheme(isDarkMode)
        stripView?.setPinnedBackground(isPinned, isDarkMode)
        popoutManager?.activeContentView?.let { applyPopoutTheme(it) }
        if (isPinned && isVisible) updateRootBackground(true)
    }

    /**
     * Apply theme colors from the webapp (JS → Kotlin direction).
     * Called via DashieNative.setSidebarThemeColors(colorsJson).
     *
     * JSON payload carries the full Dashie webapp palette (bgPrimary, bgSecondary,
     * bgTertiary, gridGap, accentPrimary, accentSecondary, textPrimary,
     * textSecondary). Legacy fields "accentColor" / "primaryColor" / "backdropColor"
     * are still accepted for back-compat but the canonical path is the full palette
     * → [com.dashieapp.Dashie.halite.theming.NativeThemeManager] → surface refresh.
     *
     * Strip backdrop tracks --grid-gap-color so the sidebar visually unifies with
     * the dashboard gap area (the "land" color in the Blue theme).
     */
    fun setThemeColors(colorsJson: String) {
        try {
            // No-op guard (Fix D1): the webapp re-pushes the current palette on
            // authoritative-resolved / dashboard-ready / webview-recreated to
            // converge slow/recreate paths. An unchanged palette arrives
            // byte-identical, so skip the re-parse + re-paint to avoid flicker.
            if (colorsJson == lastAppliedThemeColorsJson) {
                return
            }
            val json = org.json.JSONObject(colorsJson)

            fun field(name: String): String? =
                json.optString(name).takeIf { it.isNotEmpty() }

            // Populate the central palette — all surfaces read from here.
            com.dashieapp.Dashie.halite.theming.NativeThemeManager.update(
                bgPrimaryHex = field("bgPrimary"),
                bgSecondaryHex = field("bgSecondary"),
                bgTertiaryHex = field("bgTertiary"),
                gridGapHex = field("gridGap"),
                accentPrimaryHex = field("accentPrimary") ?: field("primaryColor"),
                accentSecondaryHex = field("accentSecondary") ?: field("accentColor"),
                textPrimaryHex = field("textPrimary"),
                textSecondaryHex = field("textSecondary"),
            )

            // Sidebar strip needs live update — apply now rather than wait for
            // a re-show.
            val accent = field("accentSecondary") ?: field("accentColor")
            val primary = field("accentPrimary") ?: field("primaryColor")
            // Two distinct surfaces, two distinct colors:
            //   - The sidebar PANEL (the icon strip the user sees) sources from
            //     --bg-primary so it visually unifies with the widget bg.
            //   - The sidebarBackgroundStrip View (the wider strip painted
            //     BEHIND the panel — visible as the backdrop a few pixels
            //     around the icon strip) sources from --grid-gap-color so it
            //     matches the gap area between dashboard widgets.
            // Read post-parse Ints from NativeThemeManager rather than
            // re-parsing the raw hex strings — Color.parseColor doesn't accept
            // 3-digit shorthand (#000, #fff, #ccc) which the default theme
            // uses, so re-parsing here silently no-op'd those values and left
            // themedStripBackdrop on the previous theme's grid-gap (e.g.
            // Blue's #2A2F38, which is exactly the "wrong color in dark mode"
            // bug). The manager already expanded shorthand at update time.
            val tm = com.dashieapp.Dashie.halite.theming.NativeThemeManager
            val panelColor = tm.getBgPrimary()
            val stripBackdrop = tm.getGridGap() ?: tm.getBgPrimary()
            if (accent != null) stripView?.setAccentColor(accent)
            if (primary != null) stripView?.setPrimaryColor(primary)
            // Theme the sidebar icon-button focus highlight (was hardcoded
            // orange in bg_sidebar_button.xml). Uses accentSecondary (the
            // active-indicator color) so it matches the strip's view-switcher
            // indicator bar that already tracks the theme.
            tm.getAccentSecondary()?.let { stripView?.applyButtonHighlightTheme(it) }
            if (panelColor != null) {
                stripView?.setBackdropColor(panelColor)
            }
            if (stripBackdrop != null) {
                themedStripBackdrop = stripBackdrop
                val backdropView = activity.findViewById<View>(R.id.sidebarBackgroundStrip)
                if (backdropView?.visibility == View.VISIBLE) {
                    backdropView.setBackgroundColor(currentBackdropColor())
                }
            }

            // Icon tint is painted from THIS push, atomically with the panel
            // and backdrop above — never from the separate, race-prone
            // onColorSchemeChanged→refreshTheme signal. The webapp sends an
            // explicit "isDark" (authoritative theme light/dark); older JS that
            // predates it falls back to the panel background's luminance, which
            // still guarantees the icons contrast with their panel. Recording
            // themedIsDark makes a later re-show (showPinned) keep this value
            // instead of re-reading the possibly-stale Kotlin pref.
            val paletteIsDark: Boolean = when {
                json.has("isDark") -> json.optBoolean("isDark")
                panelColor != null -> isColorDark(panelColor)
                else -> effectiveIsDark()
            }
            themedIsDark = paletteIsDark
            isDarkMode = paletteIsDark
            stripView?.applyTheme(paletteIsDark)
            // Record only after a full successful apply so a mid-apply throw
            // doesn't latch a half-applied palette as "current".
            lastAppliedThemeColorsJson = colorsJson
        } catch (e: Exception) {
            android.util.Log.w("NativeSidebar", "setThemeColors: invalid JSON: $colorsJson")
        }
    }

    /**
     * Reset the sidebar strip + backdrop to neutral (no Dashie theme). Called
     * on sign-out: the full dashboard pushes a theme color via setThemeColors
     * (themedStripBackdrop + the strip panel), and without this it persisted
     * into kiosk mode — the backdrop strip stayed the last account's brand
     * color (e.g. blue #9EB4FE) instead of reverting to neutral black/white.
     */
    fun resetThemeToNeutral() {
        Log.i(TAG, "resetThemeToNeutral: clearing pushed theme colors for kiosk/logged-out state")
        themedStripBackdrop = null
        // Neutralized — drop the no-op cache so a re-login that pushes the same
        // palette re-paints instead of being skipped as unchanged.
        lastAppliedThemeColorsJson = null
        stripView?.clearThemedColors(isPinned, isDarkMode)
        val backdropView = activity.findViewById<View>(R.id.sidebarBackgroundStrip)
        backdropView?.setBackgroundColor(currentBackdropColor())
    }

    /**
     * Paint a dedicated backdrop strip in the gap between the left edge
     * of the screen and the right edge of the sidebar — so the colored
     * backdrop sits on top of the WebView and behind the sidebar strip,
     * without any WebView/HTML transparency games.
     *
     * Width matches the effective sidebar width (so the strip + its
     * margin + the rounded-corner padding are all covered). Color
     * matches the dashboard's grid-gap-color:
     *   Light → #9eb4fe
     *   Dark  → #000000
     *
     * Hidden entirely when the sidebar is not pinned.
     */
    private fun updateRootBackground(pinned: Boolean) {
        val backdrop = activity.findViewById<View>(R.id.sidebarBackgroundStrip) ?: return
        if (!pinned || isPortraitWidgetsMode) {
            // In portrait WIDGETS mode the sidebar is hidden behind the
            // bottom bar (PortraitLayoutManager), so the colored
            // left-edge backdrop has no surface to back. Keep it GONE
            // regardless of pin state — otherwise any re-evaluation of
            // sidebar visibility (gate change, settings refresh) would
            // paint a phantom strip on the left. Kiosk / single-panel
            // keep the backdrop (they keep the side sidebar).
            backdrop.visibility = View.GONE
            return
        }
        val density = activity.resources.displayMetrics.density
        val effectiveWidthDp = SIDEBAR_WIDTH_DP * SidebarScaling.computeDeviceBoost(activity)
        val widthPx = (effectiveWidthDp * density).toInt()
        // Add a 4dp bleed on the right edge so the backdrop overlaps the
        // left edge of the layout canvas by a few pixels. Eliminates the
        // 1–2px white gap that appeared due to DPR rounding between the
        // Kotlin sidebar width (physical px) and the JS CSS offset (CSS
        // px). The canvas has a 4px __area inset so the bleed covers
        // only margin, not content.
        val bleedPx = (4f * density).toInt()
        val lp = backdrop.layoutParams as FrameLayout.LayoutParams
        lp.width = widthPx + bleedPx
        lp.gravity = Gravity.TOP or Gravity.START
        backdrop.layoutParams = lp
        backdrop.setBackgroundColor(currentBackdropColor())
        backdrop.visibility = View.VISIBLE
    }

    /**
     * Resolve the backdrop color for the current state. Brand color in
     * steady state; theme-neutral (white in light, black in dark) when
     * `backdropNeutral` is true (during WebView reloads or fullscreen-mode
     * holds, when the dashboard isn't fully painted and the brand strip
     * looks jarring against the blank/loading webview area).
     */
    private fun currentBackdropColor(): Int {
        if (backdropNeutral) {
            val c = if (isDarkMode) Color.BLACK else Color.WHITE
            Log.i(TAG, "currentBackdropColor: NEUTRAL → #${Integer.toHexString(c).uppercase()}")
            return c
        }
        // Prefer the dedicated --grid-gap-color value so the backdrop strip
        // matches the gap area between dashboard widgets. Fall back to the
        // panel's themed color (same as widget bg) if gridGap hasn't been
        // pushed, then to the original hardcoded defaults.
        themedStripBackdrop?.let {
            Log.i(TAG, "currentBackdropColor: themedStripBackdrop → #${Integer.toHexString(it).uppercase()}")
            return it
        }
        stripView?.getThemedBackdropColor()?.let {
            Log.i(TAG, "currentBackdropColor: stripView.getThemedBackdropColor → #${Integer.toHexString(it).uppercase()} (FALLBACK — gridGap not pushed)")
            return it
        }
        // No theme pushed (e.g. kiosk HA user who never logged into Dashie /
        // chose a theme). Fall back to NEUTRAL black/white rather than the old
        // brand-blue (#9eb4fe), which looked like a stray themed strip on an
        // otherwise un-themed device.
        val fallback = if (isDarkMode) Color.BLACK else Color.WHITE
        Log.i(TAG, "currentBackdropColor: HARDCODED neutral fallback → #${Integer.toHexString(fallback).uppercase()} (no theme at all)")
        return fallback
    }

    /**
     * Toggle the backdrop strip color between brand and theme-neutral.
     * Theme-neutral (white/black) is used during transitions where the
     * dashboard isn't fully painted, so the colored strip doesn't look
     * jarring against the blank webview. Safe to call repeatedly — only
     * touches the View when state actually changes.
     */
    fun setBackdropNeutral(neutral: Boolean) {
        if (backdropNeutral == neutral) return
        backdropNeutral = neutral
        val backdrop = activity.findViewById<View>(R.id.sidebarBackgroundStrip) ?: return
        if (backdrop.visibility == View.VISIBLE) {
            backdrop.setBackgroundColor(currentBackdropColor())
        }
    }

    /**
     * Visually suppress the sidebar without dropping the layout offset.
     * Used by FullscreenModeManager when JS modals (auth QR, voice
     * overlay, etc.) need to take over the screen without causing the
     * webview to shift left as the sidebar slides away.
     *
     * When suppressed:
     *   - icon strip is GONE (no interaction surface)
     *   - backdrop strip stays VISIBLE but recolored to theme-neutral,
     *     so the sidebar area visually melts into the page background
     *   - WebView margin is preserved (no re-layout)
     *
     * When restored, icons return and backdrop returns to brand color.
     */
    /**
     * Set by the visibility gate (NativeWidgetVisibilityGate.Listener) when
     * the user isn't authenticated and HA isn't configured. While suppressed
     * the sidebar is fully hidden — strip GONE, WebView leftMargin cleared.
     * On release the sidebar reverts to its pinned/overlay state.
     */
    fun setGateSuppressed(suppressed: Boolean) {
        if (gateSuppressed == suppressed) return
        gateSuppressed = suppressed
        Log.i(TAG, "setGateSuppressed($suppressed)")
        // Auth-state transitions also flip the underlying isSidebarEnabled
        // callback (linked-account state and HA-setup-complete state are the
        // inputs). Re-read it before applying — otherwise the cached
        // isEnabled from app-launch (typically false on the login screen)
        // would keep the sidebar hidden even after we unsuppress.
        isEnabled = callbacks.isSidebarEnabled()
        isPinned = callbacks.isDashBarPinned()
        applySidebarVisibility()
    }

    /**
     * Set by MainActivity from the visibility gate: true when the gate is in
     * FULL mode (the Dashie web app is loaded and its JS layout canvas owns
     * the sidebar offset). In KIOSK mode the HA shell has no JS canvas, so
     * Kotlin applies the WebView leftMargin instead. Re-applies the layout on
     * change so a FULL↔KIOSK transition immediately corrects the offset.
     */
    fun setJsLayoutOwnsOffset(owns: Boolean) {
        if (jsLayoutOwnsOffset == owns) return
        jsLayoutOwnsOffset = owns
        Log.i(TAG, "setJsLayoutOwnsOffset($owns)")
        notifyJsOfSidebarLayoutChange()
    }

    /**
     * D.52 — re-read the persisted pin state and apply visibility. Used when
     * external code (e.g., the kiosk → widgets auto-pin in
     * SettingsCallbackWiring) writes `dashMenuPinned` directly and needs the
     * visible state to update without waiting for the next app launch.
     */
    fun refreshPinnedFromPrefs() {
        isPinned = callbacks.isDashBarPinned()
        Log.i(TAG, "refreshPinnedFromPrefs → isPinned=$isPinned")
        applySidebarVisibility()
    }

    /**
     * True when the sidebar occupies layout space — i.e. it's pinned and
     * actually allowed to show. Used by both the Kotlin WebView leftMargin
     * path and the JS bridge layout offset path so that hiding via the
     * auth gate also drops the dashboard's left padding.
     */
    fun occupiesLayoutSpace(): Boolean =
        isPinned && !gateSuppressed && (isEnabled || isLocked)

    fun setVisuallySuppressed(suppressed: Boolean) {
        if (visuallySuppressed == suppressed) return
        visuallySuppressed = suppressed
        val strip = stripView ?: return

        if (suppressed) {
            popoutManager?.dismissPopout()
            strip.stripRoot.visibility = View.INVISIBLE
            setBackdropNeutral(true)
        } else {
            // Restore the strip's normal visibility based on whether the
            // sidebar should be showing at all (pinned + isVisible).
            if (isVisible) {
                strip.stripRoot.visibility = View.VISIBLE
            }
            setBackdropNeutral(false)
        }
    }

    /** Re-apply sidebar icon sizes (called from broadcast when setting changes). */
    fun applyIconSize() {
        stripView?.applyIconSize(SidebarScaling.effectiveMultiplier(activity))
    }

    /**
     * Scale a popout content view using the combined device boost × user setting.
     * Mirrors JS `transform: scale(--sidebar-boost * --menu-size)` on popout menus.
     */
    private fun applyPopoutScale(view: View) {
        val scale = SidebarScaling.effectiveMultiplier(activity)
        view.pivotX = 0f       // Scale from left edge (popout appears right of strip)
        view.pivotY = view.height.toFloat()  // Scale from bottom
        view.scaleX = scale
        view.scaleY = scale
        // Re-apply after layout since pivotY needs actual height
        view.post {
            view.pivotY = view.height.toFloat()
            view.scaleX = scale
            view.scaleY = scale
            popoutManager?.repositionActivePopout()
        }
    }

    fun destroy() {
        scope.cancel()
        popoutManager?.dismissPopout()
    }
}

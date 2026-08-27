package com.dashieapp.Dashie.controlcenter

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashieapp.Dashie.R
import kotlinx.coroutines.*
import com.dashieapp.Dashie.edition.brandName

/**
 * Native Control Center overlay — full-screen modal with 9 feature cards
 * in a 2-column grid layout.
 *
 * Mirrors the JS ControlCenterOverlay behavior:
 * - Pre-sized container (80% width, 92% height, max 690x680dp)
 * - 60s inactivity auto-close
 * - D-pad 2D grid navigation
 * - 3s polling for state refresh
 * - Card tap → routes to JS settings via bridge (for now)
 */
class ControlCenterOverlay(
    private val activity: Activity,
    private val stateProvider: ControlCenterStateProvider,
    private val onNavigateToSettings: (pageId: String) -> Unit,
    private val onOpenNativeDialog: (action: String) -> Unit,
    private val onResetScreenTimers: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onRequestCounts: (() -> Unit)? = null,
    /** Visibility gate — registered OVERLAY_FULL_ONLY (user-explicit
     *  modal, dashboard-only). Gate force-hides on page reload. */
    private val visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null,
    /** Kiosk lock: supplies the shared LockDialogs instance for the
     *  settings-PIN gate on show(). Null (or null result) = LockGate
     *  constructs its own prompt instance. */
    private val lockDialogsProvider: (() -> com.dashieapp.Dashie.halite.sidebar.dialogs.LockDialogs?)? = null,
    /** Triggers a JS-side check-subscription sync. Wired from the subscribe
     *  dialog "Check Subscription" button so users can poll status after
     *  completing checkout on their phone. Null = button only refreshes
     *  from the local SubscriptionPreferences cache. */
    private val onTriggerSubscriptionSync: (() -> Unit)? = null,
    /** True when an app update was dismissed to Control Center — surfaces a
     *  "Software Update" card at the top of the overlay. Null = no card. */
    private val isUpdatePending: (() -> Boolean)? = null,
    /** Tapped the "Software Update" card — re-shows the update banner. */
    private val onUpdateAction: (() -> Unit)? = null,
    /** Manifest versionName of the queued update (e.g. "1.1.4"), for the
     *  Software Update card label. Null = the card uses the generic label. */
    private val updateVersionName: (() -> String?)? = null,
    /** True while the update APK is currently downloading — flips the
     *  "Software Update" card to a "Downloading…" label so the user has
     *  feedback that something is happening after they tap Install Now. */
    private val isUpdateDownloading: (() -> Boolean)? = null,
    /** True when the most recent download attempt failed — flips the card
     *  to a red-dot ERROR state with "Update Failed — tap to try again"
     *  so the user has persistent feedback (Toast is easy to miss on TV). */
    private val isUpdateFailed: (() -> Boolean)? = null,
    /** True between download-complete + PackageInstaller commit and the
     *  app being replaced by the new APK — bridges the "silent install"
     *  window where the CC card would otherwise look idle while the
     *  system processes the install. */
    private val isUpdateInstalling: (() -> Boolean)? = null,
    /** Device-specific "install in progress" summary copy (Fire TV needs
     *  "reopen from home screen" framing since it doesn't auto-relaunch
     *  after sideload installs). Null = use the generic copy. */
    private val installingSummaryText: (() -> String?)? = null
) {
    companion object {
        private const val TAG = "CCOverlay"
        private const val INACTIVITY_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val CAMERA_POLL_INTERVAL_MS = 1_500L
    }

    // ── Views ───────────────────────────────────────────────────────

    private var overlayRoot: FrameLayout? = null
    private var container: LinearLayout? = null
    private var recyclerView: RecyclerView? = null
    private var footerText: TextView? = null
    private var footerBar: LinearLayout? = null
    private var hideInactiveLabel: TextView? = null
    private var hideInactiveSwitch: androidx.appcompat.widget.SwitchCompat? = null
    private var installStrip: LinearLayout? = null
    private var installStripDivider: View? = null
    private var installStripText: TextView? = null

    /**
     * STT model install strip (bottom of the CC, above the footer): renders the shared
     * [com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress] state — the surface that stays
     * reachable when the download dialog was hidden or settings closed mid-install (the ask,
     * 2026-08-18: the ~2-minute extraction otherwise runs with no visible progress anywhere).
     * Registered only while the CC is showing.
     */
    private val installListener =
        com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.Listener { snap ->
            val show = snap != null
            installStrip?.visibility = if (show) View.VISIBLE else View.GONE
            installStripDivider?.visibility = if (show) View.VISIBLE else View.GONE
            if (snap != null) {
                installStripText?.text = "${snap.label}  —  " +
                    com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.render(snap)
            }
        }

    private var adapter: ControlCenterAdapter? = null
    var isVisible: Boolean = false
        private set

    // ── Coroutines ──────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var inactivityJob: Job? = null
    private var pollJob: Job? = null
    private var cameraPollJob: Job? = null

    // ── D-pad state ─────────────────────────────────────────────────

    /** Currently focused clickable item index */
    private var focusRow = 0
    private var focusCol = 0

    // ── Lifecycle ───────────────────────────────────────────────────

    /**
     * Initialize the overlay views. Call once during Activity setup.
     * The overlay is added to activity_main.xml layout.
     */
    fun initialize() {
        overlayRoot = activity.findViewById(R.id.ccOverlayRoot) ?: return
        container = activity.findViewById(R.id.ccContainer)
        recyclerView = activity.findViewById(R.id.ccRecyclerView)
        footerText = activity.findViewById(R.id.ccFooter)
        footerBar = activity.findViewById(R.id.ccFooterBar)
        installStrip = activity.findViewById(R.id.ccInstallStrip)
        installStripDivider = activity.findViewById(R.id.ccInstallStripDivider)
        installStripText = activity.findViewById(R.id.ccInstallStripText)
        // Cancel here is the SAME cancel as the download dialog's — one flag on
        // SttInstallProgress, polled by the installer in both phases.
        activity.findViewById<View>(R.id.ccInstallStripCancel)?.setOnClickListener {
            resetInactivityTimer()
            com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.snapshot()?.let {
                snap -> com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress
                    .requestCancel(snap.familyId)
            }
        }
        hideInactiveLabel = activity.findViewById(R.id.ccHideInactiveLabel)
        hideInactiveSwitch = activity.findViewById(R.id.ccHideInactiveSwitch)

        // "Hide inactive" toggle — flips the device-local pref and rebuilds
        // the card list so off/inactive pills drop out (or return).
        val generalPrefs = com.dashieapp.Dashie.halite.preferences.GeneralPreferences(activity)
        hideInactiveSwitch?.isChecked = generalPrefs.hideInactiveControls
        activity.findViewById<View>(R.id.ccHideInactiveToggle)?.setOnClickListener {
            resetInactivityTimer()
            val next = !generalPrefs.hideInactiveControls
            generalPrefs.hideInactiveControls = next
            hideInactiveSwitch?.isChecked = next
            refreshContent()
        }

        // Register with visibility gate so page reload force-hides the
        // modal (kills ghost overlay if user reloads while CC is open).
        overlayRoot?.let {
            visibilityGate?.register(
                it,
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_FULL_ONLY
            )
        }

        // Set up RecyclerView with GridLayoutManager (2 columns)
        adapter = ControlCenterAdapter { action -> handleCardClick(action) }

        // Only show D-pad focus highlight on TV/remote devices
        val uiMode = activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK
        adapter?.dpadEnabled = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        val gridLayoutManager = GridLayoutManager(activity, 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // Section headers span full width; column headers, cards, and spacers span 1
                return when (adapter?.getItemViewType(position)) {
                    0 -> 2 // SECTION_HEADER — full width
                    5 -> 2 // PROMO_TEXT — full width
                    6 -> 2 // PROMO_BUTTON — full width
                    else -> 1 // Cards, column headers, spacers — single column
                }
            }
        }

        recyclerView?.layoutManager = gridLayoutManager
        recyclerView?.adapter = adapter

        // Backdrop click closes overlay
        overlayRoot?.setOnClickListener { hide() }
        container?.setOnClickListener { /* consume — don't close on container click */ }

        // Close button
        activity.findViewById<TextView>(R.id.ccCloseButton)?.setOnClickListener { hide() }

        // NOTE: no NativeThemeManager listener — calling refreshTheme on every
        // palette push triggered forceResourcesNightMode → config change →
        // potential dark-mode toggle loop. CC re-themes on next show() instead,
        // which covers the only realistic case (palette change while CC is
        // closed; the open-and-watch-it-change case is rare and not worth the
        // loop risk).

        Log.d(TAG, "Initialized")
    }

    /**
     * Size the container to match CSS: 80vw max 690dp, 92vh max 680dp.
     */
    private fun sizeContainer() {
        val root = overlayRoot ?: return
        val container = container ?: return
        val dm = activity.resources.displayMetrics
        val density = dm.density

        // Use root dimensions if available, fall back to display metrics
        val screenWidth = if (root.width > 0) root.width else dm.widthPixels
        val screenHeight = if (root.height > 0) root.height else dm.heightPixels

        val maxWidthPx = (690 * density).toInt()
        val maxHeightPx = (680 * density).toInt()

        val targetWidth = minOf((screenWidth * 0.80).toInt(), maxWidthPx)
        val targetHeight = minOf((screenHeight * 0.92).toInt(), maxHeightPx)

        // Display Size: render the control center's content larger without
        // growing the dialog or doing per-row adapter work. Lay the container
        // out at target/scale (smaller logical box → fewer, larger grid rows),
        // then scaleX/scaleY it back up from center. Net on-screen footprint
        // stays = target (so it never overflows the screen at any scale), but
        // the grid content appears `scale`× bigger. No-op at Display Size 100%.
        val scale = com.dashieapp.Dashie.halite.preferences.DisplaySizeScale.scale(activity)
        val logicalWidth = (targetWidth / scale).toInt()
        val logicalHeight = (targetHeight / scale).toInt()

        Log.d(TAG, "sizeContainer: screen=${screenWidth}x${screenHeight}, target=${targetWidth}x${targetHeight}, scale=$scale")

        container.layoutParams = FrameLayout.LayoutParams(logicalWidth, logicalHeight).apply {
            gravity = android.view.Gravity.CENTER
        }
        container.pivotX = logicalWidth / 2f
        container.pivotY = logicalHeight / 2f
        container.scaleX = scale
        container.scaleY = scale
    }

    /**
     * Show the control center overlay.
     */
    fun show() {
        // The visibility gate (OVERLAY_FULL_ONLY) can force overlayRoot GONE
        // on a page reload / layout-mode switch without routing through
        // hide(), which leaves isVisible stale-true. Guard on the *actual*
        // view state as well — otherwise a stale flag makes the Control
        // Center permanently unreachable after a layout-mode switch until a
        // sleep/wake re-asserts the gate (D.7).
        if (isVisible && overlayRoot?.visibility == View.VISIBLE) return

        // Kiosk lock (lockSettings): the CC is the front door to every
        // settings surface, and every caller funnels through here (sidebar,
        // JS bridge, settings-return re-show) — gate before anything renders.
        // PIN success opens a short grace window so tapping a card into
        // SettingsActivity doesn't prompt again. No-op when unlocked or
        // unlockMechanism=anyone.
        com.dashieapp.Dashie.halite.LockGate.requirePin(
            activity,
            lockDialogsProvider?.invoke()
        ) { showUnlocked() }
    }

    /** show() continuation once the kiosk-lock gate has passed. */
    private fun showUnlocked() {
        if (isVisible && overlayRoot?.visibility == View.VISIBLE) return
        if (isVisible) {
            Log.i(TAG, "show(): isVisible was stale-true (gate hid overlay) — re-syncing")
        }
        isVisible = true
        Log.d(TAG, "Showing CC overlay")

        // Cache expensive state (license, etc.) once on show — not every poll cycle
        stateProvider.refreshCachedState()

        // Request family/calendar counts from JS (async — will refresh on next poll)
        onRequestCounts?.invoke()

        // Build items first (via DiffUtil), THEN force theme refresh.
        // Order matters: refreshContent() uses DiffUtil which may skip rebind for
        // unchanged items. refreshTheme()'s notifyDataSetChanged() must come AFTER
        // to force all items to rebind with the current theme colors.
        refreshContent()
        refreshTheme()

        // Live install progress while the CC is open (addListener immediately
        // replays the current state, so a mid-install open renders at once).
        com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.addListener(installListener)

        // Show overlay — size container after layout pass (GONE→VISIBLE needs measure first)
        overlayRoot?.visibility = View.VISIBLE
        overlayRoot?.doOnLayout { sizeContainer() }

        // Start D-pad at first item
        focusRow = 0
        focusCol = 0
        adapter?.setFocus(0)

        // Start polling
        startPolling()

        // Start inactivity timer
        resetInactivityTimer()
    }

    /**
     * Hide the control center overlay.
     */
    fun hide() {
        if (!isVisible) return
        isVisible = false
        Log.d(TAG, "Hiding CC overlay")

        stopPolling()
        cancelInactivityTimer()
        com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.removeListener(installListener)
        overlayRoot?.visibility = View.GONE
        adapter?.setFocus(-1)

        onDismiss()
    }

    /**
     * Item 22: reset per-account cached state on sign-out so the next
     * user's CC doesn't show the previous account's family member count,
     * calendar accounts, etc. before fresh values arrive.
     */
    fun resetForSignOut() {
        stateProvider.resetCachedCountsForSignOut()
    }

    /**
     * Repaint the card list now if the overlay is showing. Called from the
     * JS-bridge count callbacks (family/calendar) so fresh counts render on
     * arrival instead of waiting up to POLL_INTERVAL_MS for the next poll
     * tick — the poll delay is what made the stale "Not configured" summary
     * linger visibly. Safe from any thread.
     */
    fun refreshContentIfVisible() {
        if (!isVisible) return
        activity.runOnUiThread {
            if (isVisible) refreshContent()
        }
    }

    /**
     * Resume polling and inactivity timer after returning from a native settings page
     * (where the CC stays visible behind the translucent activity).
     */
    fun resumeAfterNativeSettings() {
        if (!isVisible) return
        Log.d(TAG, "Resuming CC after native settings return")
        refreshContent()
        refreshTheme()
        startPolling()
        resetInactivityTimer()
    }

    fun destroy() {
        scope.cancel()
    }

    // ── Theme refresh ─────────────────────────────────────────────

    /**
     * Re-apply theme-aware colors to the CC overlay.
     * After resources.updateConfiguration() changes the night mode, already-inflated
     * views keep their old colors. This method re-sets backgrounds and text colors
     * from the (now-correct) resource values.
     */
    private fun refreshTheme() {
        val nightBefore = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val storedDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(activity)
        if (storedDark != null) {
            com.dashieapp.Dashie.devicecontrols.DarkModeManager.forceResourcesNightMode(activity, storedDark)
        }
        val nightAfter = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK

        // Control Center intentionally does NOT pull from the webapp theme
        // palette — per user preference, CC stays on plain light/dark
        // (Android DayNight resources) regardless of which family theme the
        // dashboard is using. Resource lookups via activity.getColor pick up
        // the night-mode-qualified variants.
        val containerBg = activity.getColor(R.color.settings_container_bg)
        val cellBg = activity.getColor(R.color.settings_cell_bg)
        val textLabel = activity.getColor(R.color.settings_text_label)
        val textValue = activity.getColor(R.color.settings_text_value)
        val textHeader = activity.getColor(R.color.settings_text_header)
        val borderColor = activity.getColor(R.color.settings_border)
        val accentColor = activity.getColor(R.color.settings_accent)
        val focusHighlight = activity.getColor(R.color.settings_focus_highlight)

        Log.w(TAG, "CCTheme refreshTheme: storedDark=$storedDark nightBefore=$nightBefore nightAfter=$nightAfter labelColor=${Integer.toHexString(textLabel)} cellBg=${Integer.toHexString(cellBg)}")

        // Re-set container background drawable, then override its color with the
        // themed container bg below.
        container?.setBackgroundResource(R.drawable.bg_cc_container)

        // Re-set nav bar text colors
        activity.findViewById<TextView>(R.id.ccTitle)?.setTextColor(textLabel)
        activity.findViewById<TextView>(R.id.ccCloseButton)?.setTextColor(accentColor)

        // Re-set separator color (the View right after the nav bar)
        val navBar = activity.findViewById<View>(R.id.ccNavBar)
        if (navBar != null) {
            val parent = navBar.parent as? ViewGroup
            if (parent != null) {
                val sepIndex = parent.indexOfChild(navBar) + 1
                if (sepIndex < parent.childCount) {
                    parent.getChildAt(sepIndex)?.setBackgroundColor(borderColor)
                }
            }
        }

        // Footer — solid background on the bar (so cards scroll behind the
        // whole row, including the Hide-inactive toggle) with semi-transparent
        // text for the device info + toggle label.
        footerBar?.setBackgroundColor(containerBg)
        val alphaColor = (textValue and 0x00FFFFFF) or (0x99 shl 24)
        footerText?.setTextColor(alphaColor)
        hideInactiveLabel?.setTextColor(alphaColor)

        // Override container's GradientDrawable color directly so themed bg
        // wins over the XML default baked into bg_cc_container.
        container?.background?.let { drawable ->
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.setColor(containerBg)
            }
        }

        // Pre-resolve colors and pass to adapter. The adapter's itemView.context
        // may have a stale resources config, so we resolve here where we control
        // both the night mode AND the webapp palette.
        adapter?.let { a ->
            a.colorLabel = textLabel
            a.colorValue = textValue
            a.colorHeader = textHeader
            a.colorCellBg = cellBg
            a.colorHighlight = focusHighlight
            a.colorFocusBg = focusHighlight
        }

        // Force adapter to rebind all visible items with the pre-resolved colors
        adapter?.notifyDataSetChanged()
    }

    // ── Content refresh ─────────────────────────────────────────────

    private fun refreshContent() {
        val isSignedIn = stateProvider.isSignedIn()
        // Dashboard-purpose gate: false for a voice-only kiosk login (haOnlyDisplay)
        // even though isSignedIn is true — that account exists for cloud voice, not
        // the dashboard, so it should NOT flip the Dashie Features section active and
        // SHOULD keep the 30-day-trial CTA up. The Account card still uses isSignedIn.
        val showsDashboard = stateProvider.showsDashboard()
        val haEnabled = stateProvider.isHaEnabled()
        // 🔴 A "host your own dashboard" device has haEnabled=false by design, but
        // this section holds the DASHBOARD URL settings — gating it on haEnabled
        // alone left such a device with no route to its own configuration at all
        // (found on a Fire tablet, 2026-08-18). Show it for custom-URL devices too.
        val showsDashboardSection = haEnabled || stateProvider.isCustomUrlMode()
        var haCards = if (showsDashboardSection) stateProvider.buildHomeAssistantCards() else emptyList()
        var dashieCards = stateProvider.buildDashieFeatureCards()
        var generalCards = stateProvider.buildGeneralCards()

        // "Hide inactive" — drop off/inactive pills for a lean kiosk view.
        // HA + Dashie feature cards keep only activated ones (ON / WARN /
        // ERROR). In General, Preferences / Screensaver & Display / Advanced
        // always show; Account shows only when signed in.
        val hideInactive = com.dashieapp.Dashie.halite.preferences.GeneralPreferences(activity).hideInactiveControls
        if (hideInactive) {
            haCards = haCards.filter { it.status != CardStatus.OFF }
            dashieCards = dashieCards.filter { it.status != CardStatus.OFF }
            generalCards = generalCards.filter { card ->
                when (card.id) {
                    "preferences", "display", "advanced" -> true
                    "account" -> isSignedIn
                    else -> card.status != CardStatus.OFF
                }
            }
        }

        val items = mutableListOf<ControlCenterAdapter.CcListItem>()

        // ── Software Update (shown whenever an update is available, including
        // during a download / install — user always has feedback). State
        // priority: installing > downloading > failed > available. ──
        if (isUpdatePending?.invoke() == true || isUpdateInstalling?.invoke() == true) {
            val version = updateVersionName?.invoke()
            val installing = isUpdateInstalling?.invoke() == true
            val downloading = isUpdateDownloading?.invoke() == true
            val failed = isUpdateFailed?.invoke() == true
            val cardStatus = when {
                installing -> CardStatus.WARN
                failed -> CardStatus.ERROR
                else -> CardStatus.WARN
            }
            val cardTitle = when {
                installing && version != null -> "Installing update — v$version…"
                installing -> "Installing update…"
                failed && version != null -> "Update Failed — v$version"
                failed -> "Update Failed"
                downloading && version != null -> "Downloading update — v$version…"
                downloading -> "Downloading update…"
                version != null -> "Update Available — v$version"
                else -> "Update Available"
            }
            val cardSummary = when {
                installing -> installingSummaryText?.invoke()
                    ?: "Please wait — Dashie will restart when done"
                failed -> "Download failed — tap to try again"
                downloading -> "Download in progress, please wait"
                else -> "Tap to see what's new"
            }
            items.add(ControlCenterAdapter.CcListItem.SectionHeader("Software Update"))
            items.add(ControlCenterAdapter.CcListItem.FeatureCard(
                ControlCenterCard(
                    "update", cardTitle, cardStatus,
                    cardSummary, "open-update"
                )
            ))
            items.add(ControlCenterAdapter.CcListItem.Spacer)
        }

        // ── Dashboard section (hidden only when neither HA nor a custom URL is
        // in use, or when every card in it has been filtered by feature
        // visibility). Named "Dashboard" rather than "Home Assistant" because HA
        // is just one of the things the dashboard can be. ──
        if (showsDashboardSection && haCards.isNotEmpty()) {
            items.add(ControlCenterAdapter.CcListItem.SectionHeader(
                ControlCenterStateProvider.SECTION_LABEL))
            haCards.forEach { card ->
                items.add(ControlCenterAdapter.CcListItem.FeatureCard(card))
            }
            if (haCards.size % 2 != 0) {
                items.add(ControlCenterAdapter.CcListItem.Spacer)
            }
        }

        // ── Dashie Features section (hidden when not signed in OR when
        // user opted into HA-only — feature data was purged on conversion,
        // so the cards have nothing to gate). ──
        // (isTvDeviceForPromo removed with the two HA-mode promos in T4 — its only
        // readers were the d-pad variants of those blocks.)
        var trialPromoTextLine: String? = null
        var trialPromoButtonLabel: String? = null
        var trialPromoAction: String? = null
        val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity)
        val isHaOnly = subPrefs.subscriptionStatus == com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_HA_ONLY
        // showsDashboard (not isSignedIn): a voice-only kiosk login is signed in but
        // is NOT a dashboard user — suppress the Dashie Features section for it.
        if (showsDashboard) {
            // Build Dashie Features header with trial/subscription subtitle.
            // The when-block sets BOTH the subtitle (used inline on touch
            // when the section header renders) AND the trialPromo* fields
            // (used at the bottom of CC for TV / for ha_only). For
            // ha_only specifically the section itself is suppressed
            // below — the promo still fires from these values.
            val dashieSubtitle: String?
            val dashieSubtitleAction: String?
            when (subPrefs.subscriptionStatus) {
                "trialing" -> {
                    val days = subPrefs.daysRemaining
                    // D.42 — use the bottom PromoText + PromoButton on every
                    // form factor (was TV-only). The tablet's inline subtitle
                    // link was a tiny tap target; the below-cards promo gives
                    // a single canonical CTA across TV and tablet.
                    dashieSubtitle = null
                    dashieSubtitleAction = null
                    trialPromoTextLine = "Trial: $days day${if (days != 1) "s" else ""} remaining"
                    trialPromoButtonLabel = "Purchase Now"
                    trialPromoAction = "purchase-subscription"
                }
                "active" -> {
                    dashieSubtitle = null
                    dashieSubtitleAction = null
                }
                "trial_expired" -> {
                    // D.42 — same unified CTA as the trialing case.
                    dashieSubtitle = null
                    dashieSubtitleAction = null
                    trialPromoTextLine = "Trial expired"
                    trialPromoButtonLabel = "Subscribe Now"
                    trialPromoAction = "purchase-subscription"
                }
                "ha_only" -> {
                    // No subtitle and no promo. The dedicated ha_only "Subscribe Now"
                    // block that used to render below General was removed in T4 — an
                    // ha_only device IS the free HA edition, so there is nothing to
                    // upsell. Unreachable in practice (showsDashboard is false for
                    // ha_only), kept for exhaustiveness.
                    dashieSubtitle = null
                    dashieSubtitleAction = null
                }
                else -> {
                    dashieSubtitle = null
                    dashieSubtitleAction = null
                }
            }
            // Skip the section entirely for ha_only — feature data was
            // purged on conversion, so the cards would have nothing to
            // back them. Promo at the bottom is the only Dashie surface.
            // Also skip if every Dashie card was filtered by feature
            // visibility (defensive — shouldn't happen for a Dashie cloud
            // user, but avoids an orphaned header if it does).
            if (!isHaOnly && dashieCards.isNotEmpty()) {
                items.add(ControlCenterAdapter.CcListItem.SectionHeader("${activity.brandName()} Features", spaced = true, subtitle = dashieSubtitle, subtitleAction = dashieSubtitleAction))
                dashieCards.forEach { card ->
                    items.add(ControlCenterAdapter.CcListItem.FeatureCard(card))
                }
                if (dashieCards.size % 2 != 0) {
                    items.add(ControlCenterAdapter.CcListItem.Spacer)
                }
            }
        }

        // ── General section ──
        items.add(ControlCenterAdapter.CcListItem.SectionHeader("General", spaced = true))
        generalCards.forEach { card ->
            // FB27: a General card that carries a status dot (e.g. the Account card at low/out
            // of credits) renders as a FeatureCard so the dot + status-colored summary show
            // (red on ERROR). Plain nav cards (showDot=false) stay NavCards.
            items.add(
                if (card.showDot) ControlCenterAdapter.CcListItem.FeatureCard(card)
                else ControlCenterAdapter.CcListItem.NavCard(card)
            )
        }
        if (generalCards.size % 2 != 0) {
            items.add(ControlCenterAdapter.CcListItem.Spacer)
        }

        // ── Free trial promo — REMOVED (brand-split T4, 2026-07-30) ──
        // Was: "Try Dashie's Calendar, widgets, and more. / Start a 30-day free trial",
        // gated on `!showsDashboard` — i.e. it rendered PRECISELY in HA/kiosk mode.
        // Dashie for Home Assistant is now free (no account, no subscription), so an
        // upsell that appears only on the free edition is the exact claim T4 exists to
        // make true. Deleted rather than mode-gated: `!showsDashboard` and "HA mode"
        // are the same predicate, so the gated form would be unconditionally dead.
        // The `start-trial` action + trialOfferDismissed pref stay — the signed-in
        // dashboard signup flow still uses them.

        // ── Trial-active / expired promo (signed in) ──
        // D.42 — was TV-only with a tablet inline-subtitle alternative;
        // now the below-cards PromoText + PromoButton is the canonical CTA
        // on every form factor (the tablet branch above was tiny tap target).
        // On the amazon flavor, keep the informational trial-remaining
        // text but hide the Purchase / Subscribe button — IAP compliance
        // requires no external payment direction.
        val isAmazonFlavor = com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon"
        if (isSignedIn &&
            trialPromoTextLine != null && trialPromoButtonLabel != null && trialPromoAction != null) {
            items.add(ControlCenterAdapter.CcListItem.PromoText(trialPromoTextLine, "", "", highlighted = true))
            if (!isAmazonFlavor) {
                items.add(ControlCenterAdapter.CcListItem.PromoButton(trialPromoButtonLabel, trialPromoAction))
            }
        }

        // ── HA-only promo — REMOVED (brand-split T4, 2026-07-30) ──
        // Was: "Subscribe to access calendar, chores, photos & more. / Subscribe Now",
        // gated on `isSignedIn && isHaOnly` — an upsell shown ONLY to HA-mode devices.
        // Same reasoning as the free-trial promo above: the condition and "HA mode" are
        // the same thing, so there is no non-HA case left to gate. The family features
        // it advertised aren't reachable in this edition anyway.

        adapter?.submitList(items)

        // Footer
        val footer = stateProvider.buildFooter()
        val footerParts = mutableListOf<String>()
        if (footer.ip.isNotEmpty()) footerParts.add("IP: ${footer.ip}")
        if (footer.appVersion.isNotEmpty()) footerParts.add("v${footer.appVersion}")
        if (footer.deviceId.isNotEmpty()) footerParts.add("ID: ${footer.deviceId}")
        footerText?.text = footerParts.joinToString("  /  ")
        footerText?.visibility = if (footerParts.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── Card click handling ─────────────────────────────────────────

    private fun handleCardClick(action: String) {
        resetInactivityTimer()

        // Native dialogs — don't navigate through JS
        when (action) {
            "open-wake-mode" -> {
                onOpenNativeDialog("wake-mode")
                return
            }
            "open-auto-brightness-settings" -> {
                onOpenNativeDialog("auto-brightness")
                return
            }
        }

        // Software update card — re-show the update banner
        if (action == "open-update") {
            hide()
            onUpdateAction?.invoke()
            return
        }

        // Trial signup
        if (action == "start-trial") {
            // Lives in src/dashie: Chickadee has no accounts and no trial, so a guarded call
            // here would still have kept the dialog AND dialog_trial_signup.xml in the
            // published APK. `hide()` is passed in — main/ keeps the overlay lifecycle.
            com.dashieapp.Dashie.edition.EditionSeams.paywall(activity).showTrialSignup { hide() }
            return
        }

        // Dismiss the free-trial promo (the "✕") — suppress it going forward.
        // Mirrors the "I'm not interested" path on the signup dialog; reset on
        // next sign-in (see JsBridgeSubscriptionDelegate.syncSubscriptionState).
        if (action == "dismiss-trial-offer") {
            com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity)
                .trialOfferDismissed = true
            refreshContent()
            return
        }

        if (action == "purchase-subscription") {
            showSubscribeFlowViaSeam()
            return
        }

        // Upsell actions — open the subscribe QR flow directly. The QR
        // dialog explains the subscription and shows the scannable URL,
        // so we skip the intermediate "feature requires subscription"
        // prompt that used to live here.
        if (action.startsWith("upsell-")) {
            showSubscribeFlowViaSeam()
            return
        }

        // Map actions to settings page IDs
        val pageMap = mapOf(
            // General section
            "open-account" to "cc-account",
            "open-preferences" to "cc-preferences",
            "open-display" to "cc-display",
            "open-advanced" to "cc-advanced",
            "open-family" to "cc-family",
            // Features section
            "open-calendar" to "cc-calendar",
            "open-ha" to "cc-ha",
            "open-video-feeds" to "cc-video-feeds",
            "open-voice-ai" to "cc-voice-ai",
            // Legacy mappings for backward compatibility
            "open-voice" to "cc-voice-ai",
            "open-voice-assistant" to "cc-voice-ai",
            "open-ai-voice" to "cc-voice-ai",
            "open-music" to "cc-music",
            "open-camera" to "cc-camera",
            "open-photos" to "cc-photos",
            "open-power" to "cc-battery-charging",
            "open-locations" to "cc-locations",
            "open-chores" to "cc-chores",
        )

        val pageId = pageMap[action] ?: return
        Log.d(TAG, "Navigating to settings page: $pageId")

        // Native settings pages launch as a separate Activity on top —
        // keep CC visible so there's no flash (it stays behind the translucent activity).
        // JS settings pages render in the WebView below the overlay, so we must hide CC.
        val nativePages = setOf(
            "cc-preferences", "cc-voice-ai", "cc-ha", "cc-music", "cc-photos", "cc-camera",
            "cc-battery-charging", "cc-video-feeds", "cc-screensaver", "cc-sleep",
            "cc-advanced", "cc-account", "cc-display", "cc-family", "cc-locations", "cc-chores", "cc-calendar"
        )
        if (pageId in nativePages) {
            // Stop timers but keep overlay visible
            stopPolling()
            cancelInactivityTimer()
            onNavigateToSettings(pageId)
        } else {
            hide()
            onNavigateToSettings(pageId)
        }
    }

    // ── Settings return ─────────────────────────────────────────────

    /**
     * Called when Settings modal closes — restore CC content.
     */
    fun onSettingsReturned(closedByInactivity: Boolean = false) {
        if (!isVisible) return

        if (closedByInactivity) {
            Log.d(TAG, "Settings timed out — hiding CC too")
            hide()
            return
        }

        Log.d(TAG, "Settings closed, restoring CC")
        container?.visibility = View.VISIBLE
        refreshContent()
        refreshTheme()
        startPolling()
        resetInactivityTimer()
    }

    // ── Polling ─────────────────────────────────────────────────────

    private fun startPolling() {
        stopPolling()

        // General poll — 3s (battery is refreshed inside buildHomeAssistantCards)
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // Re-force resources before each poll refresh to prevent
                // system config changes from reverting uiMode between polls
                val storedDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(activity)
                if (storedDark != null) {
                    com.dashieapp.Dashie.devicecontrols.DarkModeManager.forceResourcesNightMode(activity, storedDark)
                }
                refreshContent()
            }
        }

        // Camera fast-poll — 1.5s (only when camera is enabled but not streaming)
        cameraPollJob = scope.launch {
            while (isActive) {
                delay(CAMERA_POLL_INTERVAL_MS)
                // Lightweight check — avoids rebuilding all cards just to inspect camera
                if (stateProvider.isCameraWarn()) {
                    refreshContent()
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        cameraPollJob?.cancel()
        cameraPollJob = null
    }

    // ── Subscribe prompt ────────────────────────────────────────────


    /**
     * Public entry to the QR-code subscribe dialog. Called from the
     * trial-expired overlay's "Subscribe Now" button (via broadcast),
     * the upsell-* CC actions, and the Subscribe-to-Dashie row.
     *
     * **Amazon Appstore IAP compliance:** the Amazon flavor cannot
     * direct customers to any external payment method for in-app digital
     * content. All entry points to the QR/Stripe flow funnel through
     * here, so a single early-return on the amazon flavor neuters the
     * lot. The Subscribe / Manage Subscription rows are also hidden in
     * the schema, so this branch is normally unreachable on amazon — but
     * we gate it defensively in case any caller is added later.
     */
    fun openPurchaseSubscriptionFlow() {
        if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon") {
            android.util.Log.i(
                TAG,
                "Amazon flavor — purchase flow gated for IAP compliance; no-op."
            )
            return
        }
        // Brand-split T4: same funnel argument as the amazon gate, on the mode axis.
        // Reachable by BROADCAST (ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE, routed in
        // MainBroadcastManager) as well as by direct call, so gating only the UI that
        // emits it would leave the flow openable from outside this class.
        if (com.dashieapp.Dashie.halite.HaEditionGate.blockPaywall(activity, "subscribe QR flow")) return
        showSubscribeFlowViaSeam()
    }

    /**
     * Lives in src/dashie: Chickadee sells nothing, and a guarded call here would still have
     * kept the QR flow AND its two layouts in the published APK. refreshContent() is passed in
     * — main/ keeps the overlay lifecycle.
     */
    private fun showSubscribeFlowViaSeam() {
        com.dashieapp.Dashie.edition.EditionSeams.paywall(activity).showSubscribeFlow(
            scope = scope,
            onTriggerSubscriptionSync = onTriggerSubscriptionSync,
            onRefresh = { refreshContent() },
        )
    }


    // ── Inactivity timer ────────────────────────────────────────────

    private fun resetInactivityTimer() {
        cancelInactivityTimer()
        inactivityJob = scope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            Log.d(TAG, "Inactivity timeout — closing CC")
            hide()
        }
    }

    private fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    // ── D-pad navigation ────────────────────────────────────────────

    /**
     * Compute the grid column (0 or 1) for each adapter position.
     * Section headers span 2 columns (reset the column counter);
     * everything else occupies 1 column and alternates 0, 1, 0, 1...
     */
    private fun getColumnForPosition(adapterPosition: Int): Int {
        val items = adapter?.getItems() ?: return 0
        var col = 0
        for (i in 0 until adapterPosition) {
            val item = items.getOrNull(i)
            val isFullWidth = item is ControlCenterAdapter.CcListItem.SectionHeader ||
                item is ControlCenterAdapter.CcListItem.PromoText ||
                item is ControlCenterAdapter.CcListItem.PromoButton
            if (isFullWidth) {
                col = 0 // full-width item resets to start of next row
            } else {
                col = (col + 1) % 2
            }
        }
        return col
    }

    /**
     * Handle a D-pad key event.
     * Uses actual grid column positions to navigate correctly across section
     * boundaries (headers, spacers) that break simple ±2 index arithmetic.
     * @return true if the key was consumed
     */
    fun handleDpadKey(keyCode: Int): Boolean {
        if (!isVisible) return false
        resetInactivityTimer()

        val clickableItems = adapter?.getClickableItems() ?: return false
        if (clickableItems.isEmpty()) return false

        val currentIndex = adapter?.focusedIndex ?: 0

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val currentPos = clickableItems.getOrNull(currentIndex) ?: return true
                val currentCol = getColumnForPosition(currentPos)
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1

                // Find nearest clickable item in the same column in the given direction
                var bestIndex = currentIndex
                val range = if (direction > 0) {
                    (currentIndex + 1 until clickableItems.size)
                } else {
                    (currentIndex - 1 downTo 0)
                }
                for (i in range) {
                    val pos = clickableItems[i]
                    if (getColumnForPosition(pos) == currentCol) {
                        bestIndex = i
                        break
                    }
                }
                adapter?.setFocus(bestIndex)
                scrollToFocused(clickableItems, bestIndex)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val currentPos = clickableItems.getOrNull(currentIndex) ?: return true
                val currentCol = getColumnForPosition(currentPos)
                val targetCol = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) 0 else 1
                if (currentCol == targetCol) return true // already there

                // Find the closest clickable item in the target column.
                // RIGHT (col 0→1): partner is at currentPos+1, so only look forward.
                // LEFT (col 1→0): partner is at currentPos-1, so only look backward.
                var bestIndex = -1
                var bestDist = Int.MAX_VALUE
                for (i in clickableItems.indices) {
                    val pos = clickableItems[i]
                    if (getColumnForPosition(pos) != targetCol || pos == currentPos) continue
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos < currentPos) continue
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos > currentPos) continue
                    val dist = Math.abs(pos - currentPos)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIndex = i
                    }
                }
                if (bestIndex >= 0) {
                    adapter?.setFocus(bestIndex)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (currentIndex in clickableItems.indices) {
                    val adapterPosition = clickableItems[currentIndex]
                    val action = adapter?.getItemAction(adapterPosition)
                    if (action != null) {
                        handleCardClick(action)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                hide()
                return true
            }
        }
        return false
    }

    private fun scrollToFocused(clickableItems: List<Int>, focusIndex: Int) {
        if (focusIndex in clickableItems.indices) {
            val adapterPosition = clickableItems[focusIndex]
            recyclerView?.smoothScrollToPosition(adapterPosition)
        }
    }
}

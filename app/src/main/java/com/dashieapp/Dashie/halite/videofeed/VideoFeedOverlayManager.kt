package com.dashieapp.Dashie.halite.videofeed

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.dashieapp.Dashie.halite.audio.AlertSoundService
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelCoordinator
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelParticipant
import java.net.URLEncoder

/**
 * Manages Video Feed PiP overlays with unified sidebar approach.
 *
 * All feeds (triggered and manual) go to the sidebar panel, sized compactly
 * for 1-2 cards and full-height at 3+. Cards can be dragged out to free-float.
 *
 * During screensaver, ALL feeds migrate to the panel at medium size.
 * On screensaver deactivation, floating cards restore to saved positions.
 */
class VideoFeedOverlayManager(
    private val context: Context,
    private val overlayContainer: FrameLayout,
    private val haBaseUrlProvider: () -> String,
    private val haTokenProvider: () -> String,
    private val alertSoundService: AlertSoundService,
    /** Visibility gate — registered OVERLAY_KIOSK_OR_FULL per card so
     *  feeds force-hide on transition to OFF mode (login screen / logout)
     *  but stay visible across kiosk↔dashboard mode changes. Each card is
     *  registered when created and unregistered when dismissed. */
    private val visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
) : ScreensaverPanelParticipant {

    companion object {
        private const val TAG = "VideoFeedOverlayMgr"
        private const val PINNED_STAGGER_DP = 40
        private const val SCROLL_THRESHOLD = 3
        // How often to re-scan feed availability while the strip is open.
        // 30s is slow enough not to hammer HA, fast enough to pick up a feed
        // that came online since the last scan.
        private const val AVAILABILITY_RESCAN_INTERVAL_MS = 30_000L
    }

    private val activeFeedCards = LinkedHashMap<String, VideoFeedCard>()
    private val activeFeedData = LinkedHashMap<String, VideoFeedData>()

    // Phase 1: tracks card placement alongside existing state. Not yet authoritative —
    // the existing manager code still owns actual view moves. verifyPlacement() logs
    // mismatches. See build-plans/20260417_VIDEO_FEED_STATE_MACHINE_REFACTOR.md
    private val placements = VideoFeedCoordinator()
    private val autoDismissHandlers = mutableMapOf<String, Runnable>()
    private val lastDismissTime = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private val dimOverlay = VideoFeedDimOverlay(context, overlayContainer, visibilityGate).apply {
        onOverlayTapped = { hideOverlay() }
        onDismissAll = { dismissAll() }
        panelWidthProvider = { coordinator?.getFeedAreaWidth() ?: (context.resources.displayMetrics.widthPixels / 3) }
    }

    /** Saved positions for pinned feeds before screensaver migration */
    private val savedPinnedPositions = mutableMapOf<String, Pair<Int, Int>>()

    /** Saved layout params before maximize (for restore to pre-maximize position/size) */
    private val preMaximizeParams = mutableMapOf<String, FrameLayout.LayoutParams>()

    // ── Strip Mode ─────────────────────────────────────────────────────

    /** Container for the strip (positioned at bottom of overlayContainer) */
    private var stripContainer: FrameLayout? = null
    // Periodic availability re-scan while the strip is open — catches feeds
    // that come online after the initial resolve (e.g. a Dashie tablet camera
    // that was offline when the strip first opened and comes online later).
    // Posted to [handler] every AVAILABILITY_RESCAN_INTERVAL_MS; cancelled in
    // [hideStrip].
    private var availabilityRescanRunnable: Runnable? = null

    // When a notification alert fires (feedLocation="notification") with an
    // already-pinned floating card visible, we absorb both into a 2x2 grid
    // arranged on the dashboard area. These maps remember the pinned cards'
    // pre-grid layoutParams so we can restore them after all notifications
    // clear. Keyed by ruleId. Only pinned cards (isFloating=true) are stored;
    // notification alerts are disposed on dismiss so no restore needed.
    private val preGridFloatingLp = mutableMapOf<String, FrameLayout.LayoutParams>()
    // Max total cards (pinned floating + notification alerts) allowed in the
    // grid layout. Beyond this, new notification triggers are ignored.
    private val NOTIFICATION_GRID_MAX = 4
    private var stripRenderer: VideoFeedStripRenderer? = null
    private var stripDrawer: VideoFeedDrawer? = null
    private var stripDimOverlay: View? = null
    private var stripFrame: View? = null  // The strip's thumbnail bar (can be hidden for voice-only drawer)
    private var isStripVisible = false
    // TV d-pad driver for the strip + Frigate playback. Capture is engaged only
    // when the user opens the strip via toggleStrip() on a TV device.
    private var dpadController: VideoFeedDpadController? = null
    private var dpadCaptureActive = false
    /** Resolved RTSP URLs from strip init — reused by drawer/focal cards */
    private var resolvedRtspUrls = mapOf<String, String>()

    /** Callback for bottom inset changes (strip height in dp). */
    var onBottomInsetDp: ((Int) -> Unit)? = null

    /** Current display state — single source of truth for all visual properties */
    private var displayState: FeedDisplayState = FeedDisplayState.Idle

    /** Saved state before screensaver for restoration */
    private var preScreensaverState: FeedDisplayState? = null

    /** Last known state for each trigger entity (updated on every state change) */
    private val triggerEntityStates = mutableMapOf<String, String>()

    /** Global cooldown in seconds between pop-ups for the same rule (0 = no cooldown) */
    var cooldownSeconds: Int = 0

    /** Global auto-dismiss seconds for ALL feeds (0 = never auto-dismiss). This is a
     *  per-device setting that applies to every feed — the per-rule autoDismissSeconds
     *  in VideoFeedData is no longer consulted at runtime (it was never editable per
     *  feed, only a static creation default). Set from VideoFeedPreferences. */
    var autoDismissSeconds: Int = 30

    /** Global "keep showing while the trigger stays active" for ALL feeds. Per-device;
     *  supersedes the per-rule VideoFeedData.continueWhileActive at runtime. */
    var continueWhileActive: Boolean = true

    /** Display method: "sidebar" (right-aligned vertical) or "notification" (bottom-centered horizontal) */
    var feedLocation: String = "sidebar"

    /** Layout mode — drives visual arrangement of feeds */
    var feedLayout: String = "sidebar"

    /** Change layout and apply visual migration (must be called on UI thread) */
    fun setFeedLayoutAndApply(newLayout: String) {
        if (feedLayout == newLayout) return
        feedLayout = newLayout
        applyLayoutChange(newLayout)
    }

    /** Global feed size — all feeds use this size */
    var feedSize: String = "medium"

    /**
     * Whether video feed pop-ups are paused (no new triggers fire, all active
     * feeds dismissed). Persists across app restart via
     * [VideoFeedPreferences.setPopupsPaused] — writes propagate automatically
     * when [prefsProvider] is wired, initial value is loaded during manager
     * wire-up (see MediaComponentInit).
     */
    var isPaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            prefsProvider?.invoke()?.setPopupsPaused(value)
        }

    /**
     * Whether trigger alert sounds (video-feed chimes) are muted. Persists
     * across app restart via [VideoFeedPreferences.setAlertsMuted]. The
     * setter writes through to prefs whenever [prefsProvider] is wired;
     * the initial value is loaded in MediaComponentInit after wire-up.
     */
    var isAlertsMuted: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            prefsProvider?.invoke()?.setAlertsMuted(value)
        }

    /** Stream quality settings (0 = native/no scaling) */
    var streamResolution: Int = 640
    var streamFps: Int = 15

    /** Screensaver panel coordinator — set after init by HaliteComponentRegistry */
    var coordinator: ScreensaverPanelCoordinator? = null

    /** Physical scroll mode — tracks whether cards are in the scroll container */
    private var inScrollMode: Boolean = false

    // Callbacks
    var onTokenRefreshNeeded: (() -> Boolean)? = null
    var onVisibilityChanged: ((activeFeedCount: Int) -> Unit)? = null

    // ── Helpers ───────────────────────────────────────────────────

    private fun unpinnedCount(): Int =
        activeFeedData.values.count { !it.isFloating }

    private fun floatingCount(): Int =
        activeFeedData.values.count { it.isFloating }

    private fun hasUnpinnedFeeds(): Boolean =
        activeFeedData.values.any { !it.isFloating }

    // ── ScreensaverPanelParticipant ─────────────────────────────────

    override val participantId: String = "video_feed"
    override val participantPriority: Int = 2  // Below timers (0) and music (1)
    override val isScrollable: Boolean = true

    override fun getScreensaverCardCount(): Int {
        if (displayState is FeedDisplayState.Screensaver) {
            return activeFeedCards.size  // All cards on panel during screensaver
        }
        if (coordinator?.isSidebarActive == true) {
            return unpinnedCount()  // Only sidebar cards (not floating)
        }
        return 0
    }

    override fun getScreensaverCards(): List<View> {
        if (displayState is FeedDisplayState.Screensaver) {
            return activeFeedCards.values.toList()
        }
        if (coordinator?.isSidebarActive == true) {
            return activeFeedCards.entries
                .filter { activeFeedData[it.key]?.isFloating != true }
                .map { it.value }
        }
        return emptyList()
    }

    override fun onPanelItemCountChanged(totalItems: Int) {
        // Video feeds don't auto-minimize — they always stay normal size
    }

    override fun onSidebarDeactivated() {
        // Move ALL remaining cards (pinned and unpinned) back to overlayContainer.
        // Pinned cards may be on the panel (added there for z-order visibility) and
        // must be moved before the panel is hidden.
        if (coordinator?.isScreensaverActive == true) return
        activeFeedCards.entries.forEach { (_, card) ->
            if (card.parent != null && card.parent != overlayContainer) {
                (card.parent as? ViewGroup)?.removeView(card)
                overlayContainer.addView(card, card.layoutParams as? FrameLayout.LayoutParams ?: card.cardLayoutParams)
                card.isDraggable = true
                card.isResizable = true
                card.canDragToUnpin = false
            }
        }
    }

    // ── Pause / Resume ──────────────────────────────────────────────

    fun pause() {
        Log.i(TAG, "pause: dismissing all feeds and pausing triggers")
        isPaused = true
        dismissAll()
    }

    fun resume() {
        Log.i(TAG, "resume: allowing triggers again")
        isPaused = false
    }

    fun getActiveRuleIds(): List<String> = activeFeedCards.keys.toList()

    var prefsProvider: (() -> com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences?)? = null

    fun showFeedByRuleId(ruleId: String) {
        val prefs = prefsProvider?.invoke()
        if (prefs == null) {
            Log.w(TAG, "showFeedByRuleId: no prefs provider set")
            return
        }
        val rule = prefs.getRules().find { it.optString("id") == ruleId }
        if (rule == null) {
            Log.w(TAG, "showFeedByRuleId: rule not found: $ruleId")
            return
        }
        val data = VideoFeedData.fromJson(rule)
        Log.i(TAG, "showFeedByRuleId: $ruleId → ${data.displayLabel} (manual, sidebar)")
        showFeed(data, force = true, skipChime = true, manualTrigger = true)
    }

    /** Voice "show me the pool camera": the EXACT strip-tap experience — open the
     *  strip (if closed) and focus [ruleId] in the focal drawer, as if the user
     *  opened the video strip and selected that feed (2026-07-20: not the PiP
     *  alert card, not the old lightweight drawer). onStripFeedTapped brings the
     *  RTSP on-demand upgrade and the Frigate playback button with it. Safe to call
     *  right after showStrip(): showStripInternal assigns the strip refs synchronously. */
    fun showStripWithFocalFeed(ruleId: String) {
        if (!isStripVisible) {
            showStrip()
            engageDpad()   // TV parity with toggleStrip(); no-op on touch devices
        }
        onStripFeedTapped(ruleId)
    }

    /**
     * Show a single feed in a large centered overlay — lightweight path for voice commands.
     * Does NOT create the full strip with cards for all feeds (saves memory on low-end devices).
     * Creates only the drawer + dim overlay + one card.
     */
    fun showFeedInDrawerByRuleId(ruleId: String) {
        val prefs = prefsProvider?.invoke()
        if (prefs == null) {
            Log.w(TAG, "showFeedInDrawerByRuleId: no prefs provider set")
            return
        }
        val rule = prefs.getRules().find { it.optString("id") == ruleId }
        if (rule == null) {
            Log.w(TAG, "showFeedInDrawerByRuleId: rule not found: $ruleId")
            return
        }
        val baseData = VideoFeedData.fromJson(rule)
        Log.i(TAG, "showFeedInDrawerByRuleId: $ruleId → ${baseData.displayLabel} (voice, lightweight)")

        // This call builds a BRAND-NEW card. Anything already on screen for this rule (a PiP
        // card, or a card left in playback) is about to be replaced, so retire it first:
        // openFrigatePlayback resolves its card from activeFeedCards BEFORE falling back to the
        // drawer, so a stale entry there would wire playback to the dead, detached card while
        // the user stares at this new one — the clip plays, but the playback chrome never
        // appears on the visible card. Closing the old controller first also keeps its
        // onDismissCard (forget + remove, same ruleId) from wiping the placement we record below.
        activePlaybackController?.close()
        activePlaybackController = null
        activeFeedCards.remove(ruleId)
        activeFeedData.remove(ruleId)

        // A drawer/strip may already be up (a second voice open, or playback over an
        // open strip). Tear it down SYNCHRONOUSLY before building the new one:
        // reassigning stripContainer/stripDrawer without removal ORPHANS the old
        // container in overlayContainer — its X calls closeDrawer() on the NEW refs
        // (no-op), dismissAll can't see it (untracked), and only an app restart
        // clears it (found live 2026-07-20: two voice opens stacked two Pool cards).
        // hideStrip() can't be used here — its teardown nulls the refs in a 250ms
        // animation-end listener, which would null the NEW container we assign below.
        teardownStripImmediately()

        val baseUrl = haBaseUrlProvider().trimEnd('/')
        val token = haTokenProvider()
        if (baseUrl.isBlank() || token.isBlank()) {
            Log.e(TAG, "showFeedInDrawerByRuleId: missing HA base URL or token")
            return
        }

        // Merge resolved RTSP URL so the focal card uses ExoPlayer
        val rtspUrl = resolvedRtspUrls[baseData.cameraEntityId] ?: ""
        val data = if (rtspUrl.isNotBlank() && baseData.rtspUrl.isBlank()) {
            baseData.copy(rtspUrl = rtspUrl)
        } else baseData

        // Build stream URL (MJPEG fallback — used only if rtspUrl is blank)
        val widthParam = if (data.resolution > 0) "&width=${data.resolution}" else ""
        val streamUrl = when (data.streamSourceType) {
            "rtsp" -> {
                val encodedSource = java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")
                "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam&source=$encodedSource"
            }
            "go2rtc" -> {
                "$baseUrl/api/go2rtc/stream.mjpeg?src=${java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")}"
            }
            else -> {
                "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam"
            }
        }

        // Create drawer (no strip renderer, no strip cards)
        val sidebarWidth = VideoFeedStyles.dpToPx(context, VideoFeedStyles.STRIP_SIDE_PADDING_DP)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val maxWidth = screenWidth - sidebarWidth

        val drawer = VideoFeedDrawer(
            context = context,
            onClose = {
                closeDrawer()
                hideStrip()
            },
            onMaximize = { /* no-op for voice overlay */ }
        )

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(maxWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                leftMargin = sidebarWidth
            }
            clipChildren = false
        }
        container.addView(drawer.view)

        overlayContainer.addView(container)
        stripContainer = container
        stripDrawer = drawer
        stripFrame = null  // No strip frame in voice mode
        isStripVisible = true

        // Create the single large card
        val snapshotUrl = if (data.cameraEntityId.isNotBlank())
            "$baseUrl${ApiPaths.HA}/stream/snapshot/${data.cameraEntityId}"
        else ""
        val card = VideoFeedCard(
            context = context,
            overlayContainer = overlayContainer,
            data = data,
            size = "large",
            displayMethod = "strip",
            streamUrl = streamUrl,
            snapshotUrl = snapshotUrl,
            targetFps = data.fps,
            authTokenProvider = haTokenProvider,
            onTokenRefreshNeeded = onTokenRefreshNeeded,
            onMaximize = { /* no-op for voice overlay */ },
            onNormalize = { /* no-op */ },
            onDismiss = {
                closeDrawer()
                hideStrip()
            },
            onPinChanged = null
        )
        // Register with the visibility gate — gate force-hides on OFF mode
        // (login screen / logout) but stays out of the way in FULL/KIOSK.
        visibilityGate?.register(
            card,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        // Wire the play button, exactly as the strip-tap path does — without this a
        // voice-opened focal card looks identical but can't be clicked into playback.
        if (data.isFrigateCamera) {
            card.onPlaybackRequested = { openFrigatePlayback(ruleId) }
        }

        drawer.showFeed(card, ruleId)
        // Record the placement so restoreFromPlayback can route the card back to the
        // drawer; without it the coordinator takes the fall-through branch and strands
        // the card in overlayContainer.
        placements.record(ruleId, CardPlacement.DrawerFocal)
        showStripDimOverlay()

        // Same on-demand RTSP upgrade the strip-tap focal path fires — without it a
        // voice/playback-opened card stays on choppy MJPEG forever (2026-07-20).
        triggerOnDemandRtspUpgrade(card, data.cameraEntityId, rtspUrl, baseUrl)
    }

    /**
     * Voice: open a Frigate camera straight into full-screen playback at a past
     * timestamp ("show me the pool camera from ten minutes ago"). Opens the focal card
     * first — openFrigatePlayback resolves the card from the drawer — then seeks.
     *
     * Non-Frigate feeds have no recordings; the caller is expected to have checked
     * `isFrigateCamera`, but we fall back to a plain live focal open rather than
     * showing nothing.
     */
    fun showFeedPlaybackAt(ruleId: String, timestampSec: Long, onNoFootage: (() -> Unit)? = null) {
        val rule = prefsProvider?.invoke()?.getRules()?.find { it.optString("id") == ruleId }
        if (rule == null) {
            Log.w(TAG, "showFeedPlaybackAt: rule not found: $ruleId")
            return
        }
        val data = VideoFeedData.fromJson(rule)

        showFeedInDrawerByRuleId(ruleId)

        if (!data.isFrigateCamera) {
            Log.w(TAG, "showFeedPlaybackAt: ${data.displayLabel} is not a Frigate camera — opened live instead")
            return
        }

        openFrigatePlayback(ruleId)
        activePlaybackController?.openAt(timestampSec, onNoFootage)
            ?: Log.w(TAG, "showFeedPlaybackAt: playback controller did not open for $ruleId")
    }

    /**
     * Dismiss a video feed by rule ID — handles both drawer focal feeds and PiP cards.
     * Used by voice commands ("hide the pool camera").
     */
    fun dismissFeedByVoice(ruleId: String) {
        // If the drawer is showing this feed, close drawer AND strip: voice
        // "hide the X camera" means clear the camera UI, not "back out to the
        // strip" — voice show opens the full strip+focal now, so the old
        // stripFrame==null voice-mode sniff no longer distinguishes anything.
        // hideStrip() no-ops when the strip isn't visible. Releases TV d-pad
        // capture too (the voice open engaged it).
        if (stripDrawer?.activeRuleId == ruleId || ruleId.isEmpty()) {
            closeDrawer()
            hideStrip()
            releaseDpad()
            return
        }
        // Otherwise dismiss the PiP/floating card
        if (activeFeedCards.containsKey(ruleId)) {
            dismissFeed(ruleId)
            return
        }
        // Feed isn't visible — close drawer/strip if open
        closeDrawer()
        hideStrip()
        releaseDpad()
        Log.i(TAG, "dismissFeedByVoice: $ruleId not active, closed drawer+strip")
    }

    // ── Show / Dismiss ───────────────────────────────────────────────

    fun showFeed(data: VideoFeedData, force: Boolean = false, skipChime: Boolean = false, manualTrigger: Boolean = false) {
        if (isPaused && !force) {
            Log.i(TAG, "showFeed: PAUSED — ignoring trigger for rule=${data.ruleId}")
            return
        }
        Log.i(TAG, "showFeed: rule=${data.ruleId}, camera=${data.cameraEntityId}, " +
            "size=$feedSize, pinned=${data.isPinned}, manual=$manualTrigger")

        // Suppress duplicate alerts when the same camera is already visible in
        // focal view — either as a drawer focal feed (user tapped the strip)
        // or as a Frigate playback session. Triggering a second card for the
        // same camera on top of these would obscure the view the user is
        // actively looking at. Manual triggers still go through (the user
        // explicitly asked for the feed).
        if (!manualTrigger) {
            val drawerRuleId = stripDrawer?.activeRuleId
            if (drawerRuleId != null && drawerRuleId == data.ruleId) {
                Log.i(TAG, "showFeed: suppressed — camera ${data.cameraEntityId} is active as drawer focal")
                return
            }
            val playbackRuleId = activePlaybackController?.activeRuleId
            if (playbackRuleId != null && playbackRuleId == data.ruleId) {
                Log.i(TAG, "showFeed: suppressed — camera ${data.cameraEntityId} is active in Frigate playback")
                return
            }
        }

        // Cap at NOTIFICATION_GRID_MAX total cards when the incoming alert
        // would land in the notification grid. Already-present participants
        // (pinned floating + active notifications) take precedence; excess
        // new triggers are dropped.
        if (!manualTrigger && feedLocation == "notification"
            && !activeFeedCards.containsKey(data.ruleId)) {
            val gridCount = notificationGridRuleIds().size
            if (gridCount >= NOTIFICATION_GRID_MAX) {
                Log.i(TAG, "showFeed: notification grid full ($gridCount/$NOTIFICATION_GRID_MAX) — dropping trigger for ${data.ruleId}")
                return
            }
        }

        // Re-trigger: if same rule is already active, check if camera changed or continue-while-active
        if (activeFeedCards.containsKey(data.ruleId)) {
            val existingData = activeFeedData[data.ruleId]
            if (existingData != null && existingData.cameraEntityId != data.cameraEntityId) {
                Log.i(TAG, "Camera changed for ${data.ruleId}: ${existingData.cameraEntityId} → ${data.cameraEntityId}")
                dismissFeed(data.ruleId)
            } else if (continueWhileActive) {
                Log.i(TAG, "Continue-while-active for ${data.ruleId} — resetting dismiss timer")
                resetAutoDismissTimer(data.ruleId, autoDismissSeconds)
                return
            } else {
                Log.i(TAG, "Re-trigger for ${data.ruleId} — already showing, ignoring")
                return
            }
        }

        // Cooldown: skip if this rule was dismissed recently
        val lastDismiss = lastDismissTime[data.ruleId]
        val elapsedSinceDismiss = if (lastDismiss != null)
            (System.currentTimeMillis() - lastDismiss) / 1000 else -1
        Log.i(TAG, "Cooldown check for ${data.ruleId}: cooldownSeconds=$cooldownSeconds, lastDismiss=$lastDismiss, elapsed=${elapsedSinceDismiss}s")
        if (cooldownSeconds > 0 && lastDismiss != null) {
            if (elapsedSinceDismiss < cooldownSeconds) {
                Log.i(TAG, "Cooldown active for ${data.ruleId}: ${elapsedSinceDismiss}s / ${cooldownSeconds}s — skipping trigger")
                return
            }
        }

        // Build stream URL based on source type
        val baseUrl = haBaseUrlProvider().trimEnd('/')
        if (baseUrl.isBlank() || haTokenProvider().isBlank()) {
            Log.e(TAG, "Cannot show feed: missing HA base URL or token")
            return
        }
        val widthParam = if (data.resolution > 0) "&width=${data.resolution}" else ""
        val streamUrl = when (data.streamSourceType) {
            "rtsp" -> {
                val encodedSource = java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")
                "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam&source=$encodedSource"
            }
            "go2rtc" -> {
                "$baseUrl/api/go2rtc/stream.mjpeg?src=${java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")}"
            }
            else -> {
                "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam"
            }
        }
        Log.i(TAG, "Stream URL: $streamUrl (source=${data.streamSourceType})")

        val displayMethod = normalizedDisplayMethod()
        val snapshotUrl = if (data.cameraEntityId.isNotBlank())
            "$baseUrl${ApiPaths.HA}/stream/snapshot/${data.cameraEntityId}"
        else ""
        val card = VideoFeedCard(
            context = context,
            overlayContainer = overlayContainer,
            data = data,
            size = feedSize,
            displayMethod = displayMethod,
            streamUrl = streamUrl,
            snapshotUrl = snapshotUrl,
            targetFps = data.fps,
            authTokenProvider = haTokenProvider,
            onTokenRefreshNeeded = onTokenRefreshNeeded,
            onMaximize = { maximizeFeed(data.ruleId) },
            onNormalize = { normalizeFeed(data.ruleId) },
            onDismiss = { dismissFeed(data.ruleId) },
            onPinChanged = { pinned -> onPinChanged(data.ruleId, pinned) }
        )
        // Register with the visibility gate — see drawer-mode counterpart above.
        visibilityGate?.register(
            card,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        // Wire Frigate playback button (only fires if data.isFrigateCamera is true)
        if (data.isFrigateCamera) {
            card.onPlaybackRequested = { openFrigatePlayback(data.ruleId) }
        }

        val lp = card.cardLayoutParams

        // Compute the new display state after adding this feed to sidebar
        // Check coordinator for screensaver state — displayState may still be Idle
        // if screensaver activated before any feeds existed
        val isOnScreensaver = displayState is FeedDisplayState.Screensaver ||
            coordinator?.isScreensaverActive == true
        // Float layout for either: (a) user-selected feedLayout="float", or
        // (b) feedLocation="notification", which is explicitly a centered-modal
        // alert UX that shouldn't land in the sidebar panel. The screensaver
        // override still wins — active screensaver keeps the sidebar arrangement.
        val isFloatLayout = !isOnScreensaver &&
            (feedLayout == "float" || feedLocation == "notification")
        val newSidebarCount = if (isFloatLayout) unpinnedCount() else unpinnedCount() + 1
        val newState = if (isOnScreensaver) {
            FeedDisplayState.Screensaver(activeFeedCards.size + 1)
        } else if (isFloatLayout) {
            if (newSidebarCount > 0) FeedDisplayState.Sidebar(feedSize, newSidebarCount) else displayState
        } else {
            FeedDisplayState.Sidebar(feedSize, newSidebarCount)
        }

        // Determine target container based on layout mode
        var addToScrollContainer = false
        val targetContainer: ViewGroup

        // Track effective data (may be modified for float/manual mode)
        // Manual triggers are auto-pinned so the pin icon shows active and auto-dismiss is skipped
        var effectiveData = if (manualTrigger) data.copy(isPinned = true) else data

        if (isFloatLayout) {
            // Float mode: add directly to overlayContainer as free-floating
            applyInitialPinnedPosition(lp)
            targetContainer = overlayContainer
            // Mark as "floating" (user-pinned) ONLY when the user explicitly
            // chose feedLayout="float". Notification-mode alerts also land
            // in overlayContainer (centered on dashboard with dim) but are
            // ephemeral trigger pop-ups and should auto-dismiss normally.
            // Previously we set isFloating=true unconditionally, which the
            // auto-dismiss gate at line 629 treats as "user pinned — don't
            // dismiss", leaving notification alerts on screen forever.
            if (feedLayout == "float") {
                effectiveData = data.copy(isFloating = true)
            }
        } else {
            val panel = coordinator?.getPanel()
            if (panel != null) {
                if (isOnScreensaver) {
                    coordinator?.layoutMode = newState.coordinatorLayoutMode
                    coordinator?.feedSize = newState.effectiveFeedSize
                    coordinator?.onResizeScreensaver?.invoke(true, coordinator?.getFeedAreaWidth() ?: 0)
                } else if (coordinator?.isSidebarActive != true) {
                    coordinator?.activateSidebar()
                }

                constrainCardToPanel(lp, newState.effectiveFeedSize)
                val panelCount = getScreensaverCardCount()
                if (newState.useScrollMode || (panelCount + 1 >= SCROLL_THRESHOLD)) {
                    if (!displayState.useScrollMode) migrateToScrollMode()
                    addToScrollContainer = true
                    targetContainer = coordinator!!.getScrollContainer()
                } else {
                    targetContainer = panel
                }
            } else {
                applyInitialPinnedPosition(lp)
                targetContainer = overlayContainer
            }
        }

        try {
            if (addToScrollContainer) {
                targetContainer.addView(card, toLinearLayoutParams(lp))
                card.isResizable = false
            } else {
                targetContainer.addView(card, lp)
            }
            activeFeedCards[data.ruleId] = card
            activeFeedData[data.ruleId] = effectiveData
            // Phase 1 instrumentation: record placement without moving the card.
            placements.record(
                data.ruleId,
                if (isFloatLayout) CardPlacement.Floating(lp)
                else if (isOnScreensaver) CardPlacement.Screensaver(slotIndex = activeFeedCards.size - 1)
                else CardPlacement.Maximized
            )
            verifyPlacement(data.ruleId)
            // Sync card's visual pin state with effective data
            if (effectiveData.isPinned != data.isPinned) {
                card.updatePinned(effectiveData.isPinned)
            }

            // Update display state with correct count
            displayState = newState
            Log.i(TAG, "Feed card added: ${data.ruleId} (active: ${activeFeedCards.size}, state: $displayState, float: $isFloatLayout)")
            onVisibilityChanged?.invoke(activeFeedCards.size)

            // Apply card behavior based on layout mode
            if (isFloatLayout) {
                card.isDraggable = true
                card.isResizable = true
                // Floating/notification cards should show the maximize button —
                // matches CardPlacement.Floating.interactivity.tapToMaximize
                // (true) and gives the user a way to enter full-screen from the
                // notification overlay or a user-pinned floating feed.
                card.tapToMaximize = true
                card.canDragToUnpin = false
            } else {
                card.isDraggable = newState.cardsDraggable
                card.isResizable = card.isResizable && newState.cardsResizable
                card.tapToMaximize = newState.tapToMaximize
                card.canDragToUnpin = newState.cardContainer == CardContainer.PANEL && !isOnScreensaver
                card.onDragToUnpin = { dragToFloat(data.ruleId) }
            }

            // Update overlay/dismiss UI
            if (!isFloatLayout && newState.cardContainer == CardContainer.PANEL) {
                if (isOnScreensaver) {
                    // Screensaver: update bg to match medium feed width
                    coordinator?.updateBgView()
                }
                coordinator?.notifyCardAdded()
                val panel = coordinator?.getPanel()
                if (panel != null) {
                    dimOverlay.dismissStyle = effectiveDismissStyle(newState)
                    if (newState.dismissStyle != DismissStyle.NONE) {
                        dimOverlay.showDismissButtonIn(panel)
                    }
                }
            } else if (isFloatLayout && feedLocation == "notification") {
                // Notification-style alert floating over the dashboard:
                //   - Show the semi-transparent dim layer over the rest of the
                //     dashboard (same effect used in PiP/sidebar mode) so the
                //     alert reads as a modal affordance rather than a PiP.
                //   - Show the dismiss pill bottom-center in overlayContainer.
                dimOverlay.showNotification()
                dimOverlay.dismissStyle = DismissStyle.BOTTOM_CENTER
                dimOverlay.showDismissButtonIn(overlayContainer)
                // Reflow the 2x2 grid so the newly-added alert takes its
                // slot and any previously-positioned pinned / notification
                // cards move to their new slot. This also absorbs pinned
                // floating cards (saving their pre-grid lp for restore).
                recomputeNotificationLayout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add feed card", e)
            card.release()
            return
        }

        // Play trigger chime if configured (skip for manual triggers and when muted)
        Log.i(
            TAG,
            "🔔 Chime gate: ruleId=${data.ruleId} playSoundOnTrigger=${data.playSoundOnTrigger} " +
                "triggerSound='${data.triggerSound}' skipChime=$skipChime isAlertsMuted=$isAlertsMuted",
        )
        if (data.playSoundOnTrigger && !skipChime && !isAlertsMuted) {
            Log.i(TAG, "🔔 Playing chime: '${data.triggerSound}' for ${data.ruleId}")
            alertSoundService.playChime(data.triggerSound)
        }

        // Start auto-dismiss timer (pinned/floating/manual feeds don't auto-dismiss)
        if (autoDismissSeconds > 0 && !effectiveData.isPinned && !effectiveData.isFloating && !manualTrigger) {
            startAutoDismissTimer(data.ruleId, autoDismissSeconds)
        }
    }

    /** Compute the display state based on current sidebar card count */
    private fun computeSidebarState(): FeedDisplayState {
        val sidebarCount = unpinnedCount()
        return when {
            displayState is FeedDisplayState.Screensaver ->
                FeedDisplayState.Screensaver(activeFeedCards.size)
            sidebarCount > 0 -> FeedDisplayState.Sidebar(feedSize, sidebarCount)
            else -> FeedDisplayState.Idle
        }
    }

    fun dismissFeed(ruleId: String) {
        Log.i(TAG, "dismissFeed: $ruleId")
        cancelAutoDismissTimer(ruleId)
        lastDismissTime[ruleId] = System.currentTimeMillis()
        // Track whether the dismissed card was a notification-grid participant
        // so we know to reflow after removal.
        val wasInGrid = notificationGridRuleIds().contains(ruleId)

        val card = activeFeedCards.remove(ruleId)
        activeFeedData.remove(ruleId)
        placements.forget(ruleId)
        // Drop any saved pre-grid lp for this card — it's gone.
        preGridFloatingLp.remove(ruleId)
        if (card != null) {
            val wasOnPanel = card.parent != null && card.parent != overlayContainer
            val parentBefore = card.parent
            try {
                visibilityGate?.unregister(card)
                (card.parent as? ViewGroup)?.removeView(card)
                // Diagnostic: was the removeView a no-op because the parent was
                // already null? That would leave the view visible if a second
                // copy exists in the hierarchy.
                val stillHasParent = card.parent != null
                card.release()
                Log.i(TAG, "Feed card removed: $ruleId (active: ${activeFeedCards.size}, wasOnPanel: $wasOnPanel, parentBefore=${parentBefore != null}, stillHasParent=$stillHasParent, overlayChildren=${overlayContainer.childCount})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove feed card", e)
            }

            val newCount = activeFeedCards.size
            val oldState = displayState
            val newState = computeSidebarState()
            val isOnScreensaver = displayState is FeedDisplayState.Screensaver

            // Check scroll threshold after removal
            if (oldState.useScrollMode && !newState.useScrollMode) {
                migrateFromScrollMode()
            }

            onVisibilityChanged?.invoke(newCount)

            if (newCount == 0) {
                // All feeds dismissed — clean up everything
                displayState = FeedDisplayState.Idle
                if (dimOverlay.isShowing) dimOverlay.hide()
                dimOverlay.hideDismissButtonAndReset()
                if (coordinator?.isSidebarActive == true) {
                    coordinator?.deactivateSidebar()
                }
                coordinator?.notifyCardRemoved()
            } else {
                displayState = newState
                // Always notify during screensaver to ensure relayout repositions remaining cards
                if (wasOnPanel || isOnScreensaver) {
                    coordinator?.notifyCardRemoved()
                }
                // Safety: post a deferred relayout to handle any timing edge cases
                // (e.g., scroll container reflow not completing before relayout reads positions)
                if (isOnScreensaver) {
                    handler.post { coordinator?.requestRelayout() }
                }
                // Update dismiss button if sidebar still active
                if (newState.dismissStyle != DismissStyle.NONE) {
                    dimOverlay.dismissStyle = effectiveDismissStyle(newState)
                    val panel = coordinator?.getPanel()
                    if (panel != null) dimOverlay.showDismissButtonIn(panel)
                } else {
                    // No sidebar cards left — deactivate sidebar, keep floating cards
                    if (coordinator?.isSidebarActive == true) {
                        coordinator?.deactivateSidebar()
                    }
                    dimOverlay.hideDismissButtonAndReset()
                }
            }

            // Reflow the notification grid if the dismissed card was a
            // participant.
            if (wasInGrid) {
                if (notificationGridRuleIds().isEmpty()) {
                    // Last grid card gone — tear down dim + pill.
                    if (feedLocation == "notification") {
                        if (dimOverlay.isShowing) dimOverlay.hide()
                        dimOverlay.hideDismissButtonAndReset()
                    }
                } else {
                    // Still have participants — reflow the grid and keep the
                    // dim + dismiss pill visible. The default dismissFeed
                    // logic above (computeSidebarState / newState) hides the
                    // pill when only floating cards remain; override here
                    // to keep it up as long as the grid is active.
                    recomputeNotificationLayout()
                    if (feedLocation == "notification") {
                        dimOverlay.dismissStyle = DismissStyle.BOTTOM_CENTER
                        dimOverlay.showDismissButtonIn(overlayContainer)
                        if (!dimOverlay.isShowing) dimOverlay.showNotification()
                    }
                }
            }
        }
    }

    fun previewChime(soundName: String) {
        alertSoundService.playChime(soundName)
    }

    /** Dismiss auto-triggered alert pop-ups (non-pinned active cards; manual /
     *  sidebar opens are auto-pinned and kept). True if any were dismissed —
     *  lets a TV remote clear an alert it can't otherwise reach. */
    fun dismissTriggeredAlerts(): Boolean {
        val ids = activeFeedData.entries.filter { !it.value.isPinned }.map { it.key }
        if (ids.isEmpty()) return false
        ids.forEach { dismissFeed(it) }
        return true
    }

    fun dismissAll() {
        Log.i(TAG, "dismissAll: ${activeFeedCards.size} active feeds (overlayContainer children=${overlayContainer.childCount})")
        val ruleIds = activeFeedCards.keys.toList()
        ruleIds.forEach { dismissFeed(it) }

        // Safety sweep: some past bug paths have left orphan VideoFeedCard views
        // in overlayContainer that were no longer tracked in activeFeedCards, so
        // a user Dismiss-All couldn't reach them. Nuke any leftover cards.
        val orphans = mutableListOf<View>()
        for (i in 0 until overlayContainer.childCount) {
            val child = overlayContainer.getChildAt(i)
            if (child is VideoFeedCard) orphans.add(child)
        }
        if (orphans.isNotEmpty()) {
            Log.w(TAG, "dismissAll: found ${orphans.size} orphan VideoFeedCard views in overlayContainer — force-removing")
            orphans.forEach { orphan ->
                try {
                    visibilityGate?.unregister(orphan)
                    overlayContainer.removeView(orphan)
                    (orphan as? VideoFeedCard)?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to force-remove orphan card", e)
                }
            }
        }
    }

    // ── Screensaver Transitions ──────────────────────────────────────

    fun onScreensaverActivated() {
        if (activeFeedCards.isEmpty()) return
        val panel = coordinator?.getPanel() ?: return
        val count = activeFeedCards.size

        Log.i(TAG, "Screensaver activated — migrating $count feeds to panel")

        // Tear down any active Frigate playback controller so its UI
        // (timeline / event strip / playback controls) doesn't persist in
        // the mini screensaver view. Use teardownInPlace() so the controller
        // does NOT invoke onRestoreToFloating — that callback runs
        // normalizeDrawerFeed which removes the card from activeFeedCards,
        // and then the subsequent migration loop below has nothing to move.
        activePlaybackController?.let { ctrl ->
            Log.i(TAG, "  tearing down active Frigate playback for screensaver")
            ctrl.teardownInPlace()
        }
        activePlaybackController = null

        // Defensive: any card still marked PLAYBACK (shouldn't happen after the
        // teardown above, but covers any future caller that bypasses the
        // controller) gets demoted to NORMAL.
        activeFeedCards.values.forEach { card ->
            if (card.mode == VideoFeedCard.Mode.PLAYBACK) {
                card.exitPlaybackMode()
            }
        }

        // Save current state for restoration
        preScreensaverState = displayState
        val newState = FeedDisplayState.Screensaver(count)

        // Save positions of floating feeds before migration
        activeFeedCards.entries.forEach { (ruleId, card) ->
            if (activeFeedData[ruleId]?.isFloating == true) {
                val cardLp = card.layoutParams as? FrameLayout.LayoutParams
                if (cardLp != null) {
                    savedPinnedPositions[ruleId] = Pair(cardLp.leftMargin, cardLp.topMargin)
                }
            }
        }

        // Configure coordinator for screensaver
        coordinator?.layoutMode = newState.coordinatorLayoutMode
        coordinator?.feedSize = newState.effectiveFeedSize
        coordinator?.isMenuOpen = false
        // Update photo container to match medium feed width
        coordinator?.onResizeScreensaver?.invoke(true, coordinator?.getFeedAreaWidth() ?: 0)
        dimOverlay.hide()

        // Migrate all cards to panel
        val shouldScroll = newState.useScrollMode
        if (shouldScroll) {
            val scrollContainer = coordinator!!.getScrollContainer()
            activeFeedCards.values.forEach { card ->
                if (card.parent != scrollContainer) {
                    (card.parent as? ViewGroup)?.removeView(card)
                    val lp = card.cardLayoutParams
                    constrainCardToPanel(lp, newState.effectiveFeedSize)
                    scrollContainer.addView(card, toLinearLayoutParams(lp))
                }
                card.isDraggable = false
                card.isResizable = false
                card.tapToMaximize = false
                card.canDragToUnpin = false
            }
        } else {
            // Remove from any current parent first
            activeFeedCards.values.forEach { card ->
                (card.parent as? ViewGroup)?.removeView(card)
            }
            activeFeedCards.values.forEach { card ->
                val lp = card.cardLayoutParams
                constrainCardToPanel(lp, newState.effectiveFeedSize)
                panel.addView(card, lp)
                card.isDraggable = false
                card.isResizable = false
                card.tapToMaximize = false
                card.canDragToUnpin = false
            }
        }

        displayState = newState
        coordinator?.updateBgView()
        coordinator?.notifyCardAdded()
        dimOverlay.dismissStyle = effectiveDismissStyle(newState)
        dimOverlay.showDismissButtonIn(panel)

        // Phase 1 instrumentation: all cards are now in screensaver slots
        activeFeedCards.keys.forEachIndexed { i, ruleId ->
            placements.record(ruleId, CardPlacement.Screensaver(slotIndex = i))
        }
    }

    fun onScreensaverDeactivated() {
        if (displayState !is FeedDisplayState.Screensaver || activeFeedCards.isEmpty()) return

        Log.i(TAG, "Screensaver deactivated — restoring ${activeFeedCards.size} feeds")
        dimOverlay.hideDismissButtonAndReset()

        // Remove all cards from current parents and reset scroll mode
        // (we're rebuilding from scratch — migrateToScrollMode will re-enable if needed)
        activeFeedCards.values.forEach { card ->
            (card.parent as? ViewGroup)?.removeView(card)
        }
        inScrollMode = false

        // If layout was float before screensaver, restore all cards to float
        if (feedLayout == "float") {
            activeFeedCards.entries.forEach { (ruleId, card) ->
                val lp = card.cardLayoutParams
                val saved = savedPinnedPositions[ruleId]
                if (saved != null) {
                    lp.leftMargin = saved.first
                    lp.topMargin = saved.second
                    lp.gravity = Gravity.NO_GRAVITY
                } else {
                    applyInitialPinnedPosition(lp)
                }
                overlayContainer.addView(card, lp)
                card.isDraggable = true
                card.isResizable = true
                card.tapToMaximize = false
                card.canDragToUnpin = false
                val data = activeFeedData[ruleId]
                if (data != null && !data.isFloating) {
                    activeFeedData[ruleId] = data.copy(isFloating = true)
                }
            }
            displayState = FeedDisplayState.Idle
            // Deactivate sidebar if it was active
            if (coordinator?.isSidebarActive == true) coordinator?.deactivateSidebar()
        } else {
            // Configure coordinator for sidebar mode
            coordinator?.layoutMode = "sidebar"
            coordinator?.feedSize = feedSize
            coordinator?.updateBgView()

            // Restore: floating cards (isFloating) go to saved positions, sidebar cards to panel
            var sidebarCount = 0
            val panel = coordinator?.getPanel()

            activeFeedCards.entries.forEach { (ruleId, card) ->
                val isFloating = activeFeedData[ruleId]?.isFloating == true
                if (isFloating) {
                    // Was floating before screensaver — restore to saved position
                    val lp = card.cardLayoutParams
                    val saved = savedPinnedPositions[ruleId]
                    if (saved != null) {
                        lp.leftMargin = saved.first
                        lp.topMargin = saved.second
                        lp.gravity = Gravity.NO_GRAVITY
                    }
                    overlayContainer.addView(card, lp)
                    card.isDraggable = true
                    card.isResizable = true
                    card.tapToMaximize = false
                    card.canDragToUnpin = false
                } else if (panel != null) {
                    // Sidebar card — put back on panel
                    sidebarCount++
                    val lp = card.cardLayoutParams
                    constrainCardToPanel(lp, feedSize)
                    panel.addView(card, lp)
                    card.isDraggable = false
                    card.isResizable = false
                    card.tapToMaximize = true
                    card.canDragToUnpin = true
                    card.onDragToUnpin = { dragToFloat(ruleId) }
                }
            }

            val newState = if (sidebarCount > 0) {
                FeedDisplayState.Sidebar(feedSize, sidebarCount)
            } else {
                FeedDisplayState.Idle
            }
            displayState = newState

            if (sidebarCount > 0) {
                // Handle scroll mode if needed
                if (sidebarCount >= SCROLL_THRESHOLD) {
                    migrateToScrollMode()
                }
                if (coordinator?.isSidebarActive != true) coordinator?.activateSidebar()
                coordinator?.notifyCardAdded()
                dimOverlay.dismissStyle = effectiveDismissStyle(newState)
                if (newState.dismissStyle != DismissStyle.NONE && panel != null) {
                    dimOverlay.showDismissButtonIn(panel)
                }
            }
        }

        preScreensaverState = null
        savedPinnedPositions.clear()
    }

    // ── Mode Transitions ─────────────────────────────────────────────

    fun maximizeFeed(ruleId: String) {
        val card = activeFeedCards[ruleId] ?: return
        activeFeedData[ruleId] = activeFeedData[ruleId]?.copy(isMaximized = true) ?: return

        // Phase 3a: for floating cards the coordinator owns the previous-placement
        // memory (via Floating.savedLp). For non-floating cards (drawer / panel /
        // scroll), Phase 3b will migrate — until then keep writing preMaximizeParams
        // so the existing normalize paths still work.
        val currentPlacement = placements.placementOf(ruleId)
        val isFromFloating = currentPlacement is CardPlacement.Floating
        if (isFromFloating) {
            val lp = card.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                placements.recordMaximizeFromFloating(ruleId, lp)
            }
        } else {
            // Legacy path: save lp into preMaximizeParams for drawer/panel cards.
            (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                preMaximizeParams[ruleId] = FrameLayout.LayoutParams(lp)
            }
            placements.record(ruleId, CardPlacement.Maximized)
        }

        // If card is in a LinearLayout (scroll container), move to panel first.
        // Check actual parent — don't rely on inScrollMode flag which can desync.
        val cardParent = card.parent
        if (cardParent is LinearLayout) {
            val panel = coordinator?.getPanel()
            cardParent.removeView(card)
            card.setMaximized()
            if (panel != null) {
                panel.addView(card, card.cardLayoutParams)
            } else {
                overlayContainer.addView(card, card.cardLayoutParams)
            }
        } else {
            card.setMaximized()
            updateCardLayoutParams(ruleId)
        }
        cancelAutoDismissTimer(ruleId)
        // Hide layout toggle so it doesn't cover the restore button
        dimOverlay.hideDismissButton()
        verifyPlacement(ruleId)
        Log.d(TAG, "Maximized feed: $ruleId (fromFloating=$isFromFloating)")
    }

    fun normalizeFeed(ruleId: String) {
        val card = activeFeedCards[ruleId] ?: return
        card.setNormal()
        activeFeedData[ruleId] = activeFeedData[ruleId]?.copy(isMaximized = false) ?: return

        // Phase 3a: coordinator restores Floating cards atomically (reads saved lp
        // from its own Floating placement memory). Returns true if handled.
        // Also clear the legacy preMaximizeParams entry in case it was double-written.
        if (placements.transitionMaximizedToFloating(ruleId, card)) {
            preMaximizeParams.remove(ruleId)
            Log.d(TAG, "Normalized feed: $ruleId (coordinator: Floating restore)")
            return
        }

        // Legacy path (Phase 3b will migrate): drawer / panel / scroll origins.
        val savedLp = preMaximizeParams.remove(ruleId)
        if (savedLp != null && activeFeedData[ruleId]?.isFloating == true) {
            card.layoutParams = savedLp
            Log.d(TAG, "Normalized feed: $ruleId (legacy floating restore)")
            return
        }

        // Move card back to scroll container if scroll mode is active,
        // or constrain to panel size otherwise
        if (inScrollMode) {
            val scrollContainer = coordinator?.getScrollContainer()
            val panel = coordinator?.getPanel()
            if (card.parent != null && card.parent != scrollContainer && scrollContainer != null) {
                (card.parent as? ViewGroup)?.removeView(card)
                val lp = card.cardLayoutParams
                constrainCardToPanel(lp)
                scrollContainer.addView(card, toLinearLayoutParams(lp))
                card.isResizable = false
            }
        } else {
            // Constrain back to panel size and trigger relayout
            val lp = card.cardLayoutParams
            constrainCardToPanel(lp)
            // Only set FrameLayout.LayoutParams if parent accepts it
            if (card.parent !is LinearLayout) {
                card.layoutParams = lp
            }
        }

        // Restore dismiss button and layout toggle only if sidebar has cards
        val panel = coordinator?.getPanel()
        val isOnPanel = card.parent == panel || card.parent is LinearLayout
        if (isOnPanel) {
            if (displayState.dismissStyle != DismissStyle.NONE && panel != null) {
                dimOverlay.dismissStyle = effectiveDismissStyle(displayState)
                dimOverlay.showDismissButtonIn(panel)
            }
            coordinator?.notifyCardAdded() // triggers relayout to restore card positions
        }
        // Phase 1 instrumentation: normalized — either floating (saved lp) or panel-sized
        val isFloating = activeFeedData[ruleId]?.isFloating == true
        val lp = card.layoutParams as? FrameLayout.LayoutParams
        if (isFloating && lp != null) {
            placements.record(ruleId, CardPlacement.Floating(lp))
        } else {
            // Panel/scroll-hosted "normal" cards aren't yet a CardPlacement variant;
            // leave them recorded as whatever they were before (Phase 3 will add a
            // Normal/Sidebar placement).
        }
        verifyPlacement(ruleId)
        Log.d(TAG, "Normalized feed: $ruleId (onPanel=$isOnPanel)")
    }

    // ── Frigate Playback ────────────────────────────────────────────

    private var activePlaybackController: FrigatePlaybackController? = null

    private fun openFrigatePlayback(ruleId: String, tvMode: Boolean = false) {
        Log.i(TAG, "openFrigatePlayback called: $ruleId")
        // Was this opened from the lightweight VOICE drawer (no strip frame) rather than by
        // tapping a card in the strip? If so the user never opened a strip, and reopening one
        // when playback closes leaves a ghost "Video Feeds" panel on the dashboard.
        val voiceMode = stripFrame == null && isStripVisible
        // Try active sidebar cards first, then fall back to re-reading from prefs
        var card = activeFeedCards[ruleId]
        var data = activeFeedData[ruleId]
        Log.i(TAG, "  from activeFeedCards: card=${card != null}, data=${data != null}")

        if (card == null || data == null) {
            // Card might be in the drawer (not in activeFeedCards) — find it there
            val drawerCard = stripDrawer?.getCurrentCard()
            Log.i(TAG, "  fallback drawerCard: ${drawerCard != null}")
            if (drawerCard != null) {
                card = drawerCard
                val prefs = prefsProvider?.invoke()
                val rule = prefs?.getRules()?.find { it.optString("id") == ruleId }
                data = if (rule != null) VideoFeedData.fromJson(rule) else null
            }
        }

        if (card == null || data == null) {
            Log.w(TAG, "Cannot open Frigate playback: card or data not found for $ruleId")
            return
        }

        // Phase 2: atomic transition to Playback placement. The coordinator handles:
        //  - detaching from drawer's internal state (no more hideStrip "rescue" bug)
        //  - reparenting to overlayContainer with MATCH_PARENT
        //  - remembering the previous placement for restore (no (0,0) sentinel)
        //  - applying Playback's interactivity (all off)
        // The controller is constructed AFTER the transition lands.
        placements.transitionToPlayback(
            ruleId = ruleId,
            card = card,
            overlayContainer = overlayContainer,
            drawer = stripDrawer,
            enterMode = { /* card.setPlaybackMode() is called by controller.open() */ },
        )
        activeFeedCards[ruleId] = card
        activeFeedData[ruleId] = data
        verifyPlacement(ruleId)

        // Now safe to close the strip
        hideStrip()

        // Close any existing playback controller
        activePlaybackController?.close()

        activePlaybackController = FrigatePlaybackController(
            context = context,
            card = card,
            data = data,
            haBaseUrl = haBaseUrlProvider().trimEnd('/'),
            authTokenProvider = haTokenProvider,
            tvMode = tvMode
        ).apply {
            onCloseStrip = { hideStrip() }
            // Voice-opened playback closes back to the dashboard, not to a strip the user
            // never asked for (the ghost-strip bug).
            onReopenStrip = { if (!voiceMode) showStrip() else hideStrip() }
            onDismissCard = {
                activeFeedCards.remove(ruleId)
                activeFeedData.remove(ruleId)
                placements.forget(ruleId)
            }
            onRestoreToFloating = {
                activePlaybackController = null
                if (voiceMode) {
                    // Voice-opened playback has no strip to go back to — the user asked to see a
                    // clip, not to open the feeds UI. Restoring used to hand the card to the
                    // drawer and showStrip() a full 5-card strip the user never opened, then leave
                    // the (auto-pinned) card stranded on the dashboard as a LIVE feed.
                    // Exit means exit: back to the dashboard.
                    // release() as well as removeView(): restoreToFloating has already restarted
                    // the LIVE stream on the card, so just detaching it leaves an MJPEG/RTSP
                    // connection running for a card nobody can see.
                    (card.parent as? ViewGroup)?.removeView(card)
                    card.release()
                    activeFeedCards.remove(ruleId)
                    activeFeedData.remove(ruleId)
                    placements.forget(ruleId)
                    closeDrawer()
                    hideStrip()
                    Log.i(TAG, "Playback restore (voice-opened) → dismissed to dashboard")
                } else {
                // Phase 2: restore is atomic via the coordinator. If the previous
                // placement was DrawerFocal, we still need the drawer-specific
                // restore (showStrip + drawer.showFeed) because the drawer
                // owns its UI; the coordinator just records the placement.
                val prev = placements.previousPlacementFor(ruleId)
                placements.transitionFromPlayback(
                    ruleId = ruleId,
                    card = card,
                    exitMode = { /* card.exitPlaybackMode() is called inside controller.restoreToFloating */ },
                    restoreToDrawer = {
                        // Drawer restoration: detach card from its current parent
                        // (overlayContainer in the playback case) FIRST, otherwise
                        // drawer.showFeed's addView hits "child already has a parent".
                        (card.parent as? ViewGroup)?.removeView(card)
                        // Reset the interactivity flags to drawer defaults before
                        // handing off — showFeed's buildDrawerContent expects these.
                        card.isDraggable = true
                        card.isResizable = true
                        card.tapToMaximize = false
                        card.canDragToUnpin = false
                        card.canDragUpToFloat = false
                        // Also reset layoutParams to something sensible — the drawer
                        // overrides width/height but the x/y translation from full-
                        // screen can leak through.
                        card.translationX = 0f
                        card.translationY = 0f
                        // Show the strip first so the drawer view is attached to the
                        // window, then hand the card back.
                        if (!isStripVisible) showStrip()
                        stripDrawer?.showFeed(card, ruleId)
                        stripRenderer?.setActiveFeed(ruleId)
                        showStripDimOverlay()
                        activeFeedCards.remove(ruleId)
                        activeFeedData.remove(ruleId)
                    },
                )
                // Phase 2 note: if prev was Floating/Maximized, the coordinator
                // already set the layout params. If prev was None (first-time open
                // path not covered here) the card stays wherever the controller
                // left it.
                Log.i(TAG, "Playback restore → previous placement was ${prev.name}")
                }
            }
            onAutoPin = {
                // Pin the feed so sidebar close doesn't dismiss it. NOT for voice-opened
                // playback: there is no sidebar to close, and the pin is exactly what made
                // hideStrip() "rescue" the card into a stranded floating LIVE feed on teardown.
                if (!voiceMode && activeFeedData[ruleId]?.isPinned != true) {
                    onPinChanged(ruleId, true)
                    card.updatePinned(true)
                }
            }
        }
        activePlaybackController?.open()
        Log.i(TAG, "Opened Frigate playback for $ruleId (camera: ${data.frigateCameraName})")

        // Live audio in playback requires RTSP via ExoPlayer (MJPEG has no audio
        // track). When opening playback directly from the strip the card is still
        // in MJPEG mode — fire the same on-demand resolve as the focal-drawer
        // path so the live stream upgrades to RTSP and audio becomes available.
        val cachedRtspUrl = resolvedRtspUrls[data.cameraEntityId] ?: ""
        triggerOnDemandRtspUpgrade(card, data.cameraEntityId, cachedRtspUrl, haBaseUrlProvider().trimEnd('/'))
    }

    /**
     * Hit /api/dashie/stream/resolve (no check_only) to obtain an RTSP URL for
     * [entityId], cache it in [resolvedRtspUrls], and call [VideoFeedCard.upgradeToRtsp]
     * on the UI thread. No-op if [cachedRtspUrl] is already non-blank or [entityId]
     * is empty. Used by both focal-drawer open and Frigate playback open paths.
     */
    private fun triggerOnDemandRtspUpgrade(
        card: VideoFeedCard,
        entityId: String,
        cachedRtspUrl: String,
        baseUrl: String
    ) {
        Log.i(TAG, "On-demand check: cachedRtspUrl='$cachedRtspUrl' entityId='$entityId'")
        if (cachedRtspUrl.isNotBlank() || entityId.isBlank()) return
        Log.i(TAG, "Starting on-demand RTSP resolve for $entityId")
        Thread {
            try {
                val freshToken = haTokenProvider()
                if (freshToken.isBlank()) {
                    Log.w(TAG, "On-demand resolve: empty HA token")
                    return@Thread
                }
                val resolveUrl = "$baseUrl${ApiPaths.HA}/stream/resolve/$entityId"
                Log.i(TAG, "On-demand resolve URL: $resolveUrl")
                val request = okhttp3.Request.Builder()
                    .url(resolveUrl)
                    .header("Authorization", "Bearer $freshToken")
                    .build()
                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    Log.i(TAG, "On-demand resolve response: ${response.code} for $entityId")
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        Log.i(TAG, "On-demand resolve body: $body")
                        val json = org.json.JSONObject(body)
                        val rtspUrl = json.optString("rtsp_url", "")
                        if (rtspUrl.isNotBlank() && rtspUrl != "null") {
                            resolvedRtspUrls = resolvedRtspUrls + (entityId to rtspUrl)
                            Log.i(TAG, "On-demand RTSP resolved: $entityId → $rtspUrl")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                card.upgradeToRtsp(rtspUrl)
                            }
                        } else {
                            Log.i(TAG, "On-demand resolve returned no RTSP URL for $entityId")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-demand RTSP resolve failed for $entityId: ${e.message}")
            }
        }.start()
    }


    // ── Pin / Unpin ──────────────────────────────────────────────────

    private fun onPinChanged(ruleId: String, pinned: Boolean) {
        activeFeedData[ruleId] = activeFeedData[ruleId]?.copy(isPinned = pinned) ?: return
        if (pinned) {
            cancelAutoDismissTimer(ruleId)
            Log.i(TAG, "Feed pinned: $ruleId — auto-dismiss cancelled")
        } else {
            val data = activeFeedData[ruleId] ?: return
            // Only restart timer if card is in the sidebar (not floating)
            if (autoDismissSeconds > 0 && !data.isFloating) {
                startAutoDismissTimer(ruleId, autoDismissSeconds)
                Log.i(TAG, "Feed unpinned: $ruleId — auto-dismiss timer restarted (${autoDismissSeconds}s)")
            }
        }
    }

    // ── Drag to Float ──────────────────────────────────────────────────

    /**
     * Called when a card is dragged out of the sidebar panel.
     * Detaches the card from the panel, positions it at its visual location in overlayContainer,
     * and makes it free-floating (pinned, draggable, resizable).
     */
    private fun dragToFloat(ruleId: String) {
        val card = activeFeedCards[ruleId] ?: return
        val data = activeFeedData[ruleId] ?: return
        if (coordinator?.isScreensaverActive == true) return

        Log.i(TAG, "dragToFloat: $ruleId")

        // Get card's visual screen position (includes translationX/Y from drag gesture)
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        val containerLoc = IntArray(2)
        overlayContainer.getLocationOnScreen(containerLoc)

        // Remove from current parent (panel or scroll container)
        (card.parent as? ViewGroup)?.removeView(card)

        // Reset translation from drag gesture
        card.translationX = 0f
        card.translationY = 0f

        // Position at release location — add to panel (above bgView) if sidebar active,
        // otherwise overlayContainer. Panel is at higher z-order than overlayContainer.
        val lp = card.cardLayoutParams
        lp.gravity = Gravity.NO_GRAVITY
        lp.leftMargin = loc[0] - containerLoc[0]
        lp.topMargin = loc[1] - containerLoc[1]
        lp.rightMargin = 0
        lp.bottomMargin = 0
        val panel = coordinator?.getPanel()
        if (panel != null && coordinator?.isSidebarActive == true) {
            panel.addView(card, lp)
        } else {
            overlayContainer.addView(card, lp)
        }

        // Mark as floating + auto-pin, cancel auto-dismiss
        if (!data.isFloating || !data.isPinned) {
            activeFeedData[ruleId] = data.copy(isFloating = true, isPinned = true)
            card.updatePinned(true)
            cancelAutoDismissTimer(ruleId)
        }

        // Make free-floating
        card.isDraggable = true
        card.isResizable = true
        card.canDragToUnpin = false
        card.tapToMaximize = false

        // Notify coordinator of card removal from panel
        coordinator?.notifyCardRemoved()

        // Check scroll mode threshold
        if (inScrollMode && unpinnedCount() < SCROLL_THRESHOLD) {
            migrateFromScrollMode()
            // Cards moved from scroll to panel need a relayout to be positioned correctly
            coordinator?.notifyCardAdded()
        }

        // Update display state based on remaining sidebar cards
        displayState = computeSidebarState()

        // If no sidebar cards remain, deactivate sidebar and clean up
        if (!hasUnpinnedFeeds() && coordinator?.isSidebarActive == true) {
            coordinator?.deactivateSidebar()
            dimOverlay.hideDismissButtonAndReset()
        } else {
            // Update dismiss button position to match resized sidebar
            val panel = coordinator?.getPanel()
            if (panel != null && displayState.dismissStyle != DismissStyle.NONE) {
                dimOverlay.dismissStyle = effectiveDismissStyle(displayState)
                dimOverlay.showDismissButtonIn(panel)
            }
        }
    }

    // ── Scroll Mode Migration ──────────────────────────────────────────

    /** Convert FrameLayout.LayoutParams to LinearLayout.LayoutParams for scroll container */
    private fun toLinearLayoutParams(flp: FrameLayout.LayoutParams): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(flp.width, flp.height).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val gap = VideoFeedStyles.dpToPx(context, VideoFeedStyles.SIDEBAR_CARD_GAP_DP)
            topMargin = gap / 2
            bottomMargin = gap / 2
        }
    }

    /** Migrate existing panel-direct video feed cards into the scroll container */
    private fun migrateToScrollMode() {
        if (inScrollMode) return
        val scrollContainer = coordinator?.getScrollContainer() ?: return
        val panel = coordinator?.getPanel() ?: return

        Log.i(TAG, "Migrating to scroll mode")
        inScrollMode = true

        // Collect cards currently on the panel directly (not already in scroll or floating)
        // During screensaver, all cards are on the panel regardless of pin status
        val isOnScreensaver = coordinator?.isScreensaverActive == true
        val cardsToMigrate = activeFeedCards.entries.filter { (ruleId, card) ->
            card.parent == panel && (isOnScreensaver || activeFeedData[ruleId]?.isFloating != true)
        }

        for ((_, card) in cardsToMigrate) {
            panel.removeView(card)
            val lp = card.cardLayoutParams
            constrainCardToPanel(lp)
            scrollContainer.addView(card, toLinearLayoutParams(lp))
            card.isResizable = false
        }
    }

    /** Migrate video feed cards from scroll container back to direct panel placement */
    private fun migrateFromScrollMode() {
        if (!inScrollMode) return
        val scrollContainer = coordinator?.getScrollContainer() ?: return
        val panel = coordinator?.getPanel() ?: return

        Log.i(TAG, "Migrating from scroll mode")
        inScrollMode = false

        val cardsInScroll = mutableListOf<VideoFeedCard>()
        for (i in 0 until scrollContainer.childCount) {
            (scrollContainer.getChildAt(i) as? VideoFeedCard)?.let { cardsInScroll.add(it) }
        }

        for (card in cardsInScroll) {
            scrollContainer.removeView(card)
            val lp = card.cardLayoutParams
            constrainCardToPanel(lp)
            panel.addView(card, lp)
            card.isResizable = true
        }
    }

    // ── Position Calculation ─────────────────────────────────────────

    private fun normalizedDisplayMethod(): String {
        return when (feedLocation) {
            "sidebar", "notification" -> feedLocation
            else -> "sidebar"
        }
    }

    /** Hide overlay and dismiss all feeds (callback from dim overlay tap) */
    private fun hideOverlay() {
        Log.i(TAG, "hideOverlay: dismissing all feeds")
        dimOverlay.hide()
        dismissAll()
    }

    /** Set whether the camera popout menu is open — no-op in unified sidebar mode */
    fun setMenuOpen(open: Boolean) {
        // Grid mode removed — menu state no longer affects feed layout
    }

    /** Apply visual layout change when feedLayout property changes */
    /**
     * Phase 1 instrumentation: cross-check a card against its recorded placement.
     * Logs a warning via VideoFeedCoordinator when parent/mode/flags don't match.
     * Safe to call at any time; no-op if the ruleId isn't tracked yet.
     */
    private fun verifyPlacement(ruleId: String) {
        val card = activeFeedCards[ruleId] ?: return
        placements.verifyAgainstCard(
            ruleId = ruleId,
            card = card,
            overlayContainer = overlayContainer,
            drawerContainer = stripDrawer?.view,
            screensaverContainer = null,  // wired when screensaver host is exposed
            panelContainer = coordinator?.getPanel(),
            scrollContainer = coordinator?.getScrollContainer(),
        )
    }

    private fun applyLayoutChange(newLayout: String) {
        if (activeFeedCards.isEmpty()) return
        if (coordinator?.isScreensaverActive == true) return  // Screensaver forces sidebar

        Log.i(TAG, "applyLayoutChange: → $newLayout (${activeFeedCards.size} cards)")

        when (newLayout) {
            "float" -> migrateAllToFloat()
            "sidebar", "grid" -> migrateAllToSidebar()
        }
    }

    /** Move all cards from panel/scroll to overlayContainer as free-floating */
    private fun migrateAllToFloat() {
        // Deactivate sidebar first
        if (inScrollMode) migrateFromScrollMode()

        val panel = coordinator?.getPanel()
        activeFeedCards.entries.forEach { (ruleId, card) ->
            if (card.parent != null && card.parent != overlayContainer) {
                (card.parent as? ViewGroup)?.removeView(card)
                val lp = card.cardLayoutParams
                applyInitialPinnedPosition(lp)
                overlayContainer.addView(card, lp)
            }
            card.isDraggable = true
            card.isResizable = true
            card.tapToMaximize = false
            card.canDragToUnpin = false
            // Mark as floating so they stay free-floating
            if (activeFeedData[ruleId]?.isFloating != true) {
                activeFeedData[ruleId] = activeFeedData[ruleId]?.copy(isFloating = true) ?: return@forEach
                cancelAutoDismissTimer(ruleId)
            }
        }

        // Deactivate sidebar and hide dismiss
        if (coordinator?.isSidebarActive == true) {
            coordinator?.deactivateSidebar()
        }
        dimOverlay.hideDismissButtonAndReset()
        displayState = FeedDisplayState.Idle  // No sidebar cards
    }

    /** Move all floating cards back to sidebar panel */
    private fun migrateAllToSidebar() {
        val panel = coordinator?.getPanel() ?: return

        if (coordinator?.isSidebarActive != true) {
            coordinator?.activateSidebar()
        }

        var sidebarCount = 0
        activeFeedCards.entries.forEach { (ruleId, card) ->
            // Clear floating state so card goes back to sidebar behavior
            if (activeFeedData[ruleId]?.isFloating == true) {
                activeFeedData[ruleId] = activeFeedData[ruleId]?.copy(isFloating = false) ?: return@forEach
            }
            sidebarCount++

            if (card.parent != null && card.parent != panel) {
                (card.parent as? ViewGroup)?.removeView(card)
                val lp = card.cardLayoutParams
                constrainCardToPanel(lp, feedSize)
                panel.addView(card, lp)
            }
            card.isDraggable = false
            card.isResizable = false
            card.tapToMaximize = true
            card.canDragToUnpin = true
            card.onDragToUnpin = { dragToFloat(ruleId) }
        }

        val newState = FeedDisplayState.Sidebar(feedSize, sidebarCount)
        displayState = newState

        // Handle scroll mode if needed
        if (sidebarCount >= SCROLL_THRESHOLD) {
            migrateToScrollMode()
        }

        coordinator?.notifyCardAdded()
        dimOverlay.dismissStyle = effectiveDismissStyle(newState)
        if (newState.dismissStyle != DismissStyle.NONE) {
            dimOverlay.showDismissButtonIn(panel)
        }
    }

    /** Constrain card layout params to fit within the panel feed area */
    private fun constrainCardToPanel(lp: FrameLayout.LayoutParams, sizeOverride: String? = null) {
        val s = VideoFeedStyles
        val effectiveSize = sizeOverride ?: displayState.effectiveFeedSize
        val feedAreaWidth = coordinator?.getFeedAreaWidth() ?: (context.resources.displayMetrics.widthPixels / 3)
        val margin = s.dpToPx(context, s.MARGIN_DP)
        lp.width = minOf(s.cardWidthPx(context, effectiveSize), feedAreaWidth - 2 * margin)
        val videoHeight = (lp.width / VideoFeedStyles.ASPECT_RATIO).toInt()
        lp.height = videoHeight + s.dpToPx(context, s.LABEL_HEIGHT_DP)
    }

    /** Apply initial position for a free-floating pinned feed (right side, staggered) */
    /**
     * Resolve the effective [DismissStyle] for a display state, accounting for
     * the current [feedLocation]. When the user has chosen "notification"
     * display method, an active alert floats over the dashboard and needs a
     * bottom-center dismiss pill (not the sidebar-matching right-side one).
     *
     * Screensaver state is excluded: when screensaver activates, video feeds
     * are forced into the sidebar panel regardless of feedLocation preference,
     * so the dismiss pill needs to match the panel (SIDEBAR_WIDTH). Previously
     * this was overriding to BOTTOM_CENTER even in screensaver mode, leaving
     * the dismiss pill in the wrong position after the video migrated.
     */
    private fun effectiveDismissStyle(state: FeedDisplayState): DismissStyle {
        val raw = state.dismissStyle
        val isScreensaver = state is FeedDisplayState.Screensaver
        if (!isScreensaver && feedLocation == "notification" && raw == DismissStyle.SIDEBAR_WIDTH) {
            return DismissStyle.BOTTOM_CENTER
        }
        return raw
    }

    // ── Notification Grid Layout ─────────────────────────────────────────
    //
    // When a notification alert enters while there are pinned floating cards
    // visible, or when multiple notification alerts are active, the
    // participating cards arrange into a 2x2 grid centered on the dashboard
    // area (screen minus left sidebar). This prevents a notification from
    // hiding an existing pinned card and gives a clean multi-alert layout.
    //
    // Progression:
    //   1 card  — centered (full dashboard width, card aspect)
    //   2 cards — side-by-side row [top=middle vertically]
    //   3-4 cards — 2x2 grid
    //
    // Cards that participate in the grid:
    //   - Notification alerts (feedLocation="notification" trigger-shown)
    //   - User-pinned floating cards (data.isFloating == true)
    //
    // Behaviors:
    //   - Pinned card's pre-grid layout params are saved in [preGridFloatingLp]
    //     before its first move into the grid; restored when all notifications
    //     have dismissed.
    //   - Cards in the grid can't be dragged (temporarily locked).
    //   - Cards still accept tap-to-maximize and per-card dismiss.

    /**
     * RuleIds participating in the notification grid layout, in LinkedHashMap
     * insertion order so slots are deterministic across reflows. Only active
     * when feedLocation=notification — in sidebar mode, floating cards use
     * the legacy floating layout, not the grid.
     */
    private fun notificationGridRuleIds(): List<String> {
        if (feedLocation != "notification") return emptyList()
        return activeFeedCards.keys.toList()
    }

    /**
     * Compute and apply layoutParams for one slot in the notification grid.
     * Cell arrangement:
     *   n=1 → full-width single slot
     *   n=2 → two columns, single row
     *   n=3 → 2x2 with one empty slot (positions 0,1,2 filled)
     *   n=4 → 2x2 full
     */
    private fun applyGridSlotPosition(
        lp: FrameLayout.LayoutParams,
        slotIndex: Int,
        totalSlots: Int,
    ) {
        val s = VideoFeedStyles
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val sidebar = s.dpToPx(context, s.STRIP_SIDE_PADDING_DP)
        val dashboardW = screenWidth - sidebar
        val gap = s.dpToPx(context, 12)
        // Top margin only needs room for the dim overlay breathing room.
        // Bottom margin must clear the "Dismiss Cameras" pill (24dp from
        // bottom + 60dp height + ~24dp gap = 108dp minimum).
        val topMargin = s.dpToPx(context, 48)
        val bottomMargin = s.dpToPx(context, 120)
        val usableH = screenHeight - topMargin - bottomMargin

        val cols = if (totalSlots <= 1) 1 else 2
        val rows = if (totalSlots <= 2) 1 else 2

        val cellW = (dashboardW - (cols - 1) * gap) / cols
        val cellH = (usableH - (rows - 1) * gap) / rows

        val row = slotIndex / cols
        val col = slotIndex % cols

        // Target card width driven by feedSize setting (percent of screen).
        // Grid cell acts as a cap — only shrink a card below its target size
        // if it can't fit in the cell (e.g., XL at 75% with 4 cards). Small
        // and medium cards stay their natural size even in a multi-card grid.
        // Cards' intrinsic aspect is VideoFeedStyles.ASPECT_RATIO (16:9).
        val aspect = VideoFeedStyles.ASPECT_RATIO
        val targetW = s.cardWidthPx(context, feedSize)
        val targetH = (targetW / aspect).toInt()

        // Only clamp when the target genuinely exceeds the cell. Otherwise
        // preserve the intrinsic target size so picking "small" doesn't get
        // overridden by a large cell in a 1-up grid.
        val needsClamp = targetW > cellW || targetH > cellH
        val fitW: Int
        val fitH: Int
        if (needsClamp) {
            val boundedW = targetW.coerceAtMost(cellW)
            val boundedH = targetH.coerceAtMost(cellH)
            if (boundedW.toFloat() / boundedH > aspect) {
                fitH = boundedH
                fitW = (boundedH * aspect).toInt()
            } else {
                fitW = boundedW
                fitH = (boundedW / aspect).toInt()
            }
        } else {
            fitW = targetW
            fitH = targetH
        }

        // Horizontal: center the cell within dashboard area
        val cellLeft = sidebar + col * (cellW + gap)
        val cellTop = topMargin + row * (cellH + gap)

        // Center card within its cell (accounts for aspect-fit letterbox)
        lp.gravity = Gravity.NO_GRAVITY
        lp.width = fitW
        lp.height = fitH
        lp.leftMargin = cellLeft + (cellW - fitW) / 2
        lp.topMargin = cellTop + (cellH - fitH) / 2
        lp.rightMargin = 0
        lp.bottomMargin = 0
    }

    /**
     * Arrange all notification-grid-eligible cards (pinned floating + active
     * notification alerts) into the 2x2 grid. Called on every notification
     * add/dismiss so the layout reflows to match the current count.
     *
     * When there are zero participating cards, this is a no-op. When there
     * are only pinned cards and no notifications, we restore each pinned
     * card's pre-grid layoutParams so it snaps back to its user-chosen spot.
     */
    private fun recomputeNotificationLayout() {
        val ruleIds = notificationGridRuleIds()
        if (ruleIds.isEmpty()) return

        // Keep the grid layout active as long as any card participates —
        // don't restore pinned cards to their floating position when all
        // notification alerts have dismissed. Keeping the grid layout
        // preserves user mental model of "notification mode is active until
        // I explicitly dismiss". Remaining pinned cards naturally flow to
        // the single-centered slot, and the dismiss pill stays visible so
        // the user can close the last card via Dismiss Cameras.
        //
        // Pinned cards are restored to their saved floating layout only
        // when the grid participant count drops to zero (user dismissed
        // them individually, or via the pill).

        // Active layout — apply grid positions to each participating card.
        val totalSlots = ruleIds.size.coerceAtMost(NOTIFICATION_GRID_MAX)
        ruleIds.take(totalSlots).forEachIndexed { slotIndex, ruleId ->
            val card = activeFeedCards[ruleId] ?: return@forEachIndexed
            val data = activeFeedData[ruleId] ?: return@forEachIndexed

            // Save pinned card's pre-grid lp the first time we move it.
            if (data.isFloating && !preGridFloatingLp.containsKey(ruleId)) {
                val currentLp = card.layoutParams as? FrameLayout.LayoutParams
                if (currentLp != null) {
                    preGridFloatingLp[ruleId] = FrameLayout.LayoutParams(currentLp)
                }
            }

            val lp = (card.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(0, 0)
            applyGridSlotPosition(lp, slotIndex, totalSlots)
            card.layoutParams = lp
            // Cards in grid can't be dragged — locked until notifications clear.
            card.isDraggable = false
            card.isResizable = false
            Log.i(TAG, "NotificationGrid: $ruleId -> slot $slotIndex/$totalSlots " +
                "size=${lp.width}x${lp.height} at (${lp.leftMargin},${lp.topMargin})")
        }
    }

    private fun applyInitialPinnedPosition(lp: FrameLayout.LayoutParams) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        if (feedLocation == "notification") {
            // "Notification" display method: center the alert on the dashboard area
            // (the view to the right of the left sidebar) so it reads as a modal
            // alert rather than a corner PiP. Dismiss-all button is positioned
            // separately at bottom-center (see VideoFeedDimOverlay.BOTTOM_CENTER).
            val sidebarWidth = VideoFeedStyles.dpToPx(context, VideoFeedStyles.STRIP_SIDE_PADDING_DP)
            val dashboardWidth = screenWidth - sidebarWidth
            val stagger = VideoFeedStyles.dpToPx(context, PINNED_STAGGER_DP) * floatingCount()
            lp.gravity = Gravity.NO_GRAVITY
            lp.leftMargin = sidebarWidth + (dashboardWidth - lp.width) / 2 + stagger
            lp.topMargin = (screenHeight - lp.height) / 2 + stagger
            lp.rightMargin = 0
            lp.bottomMargin = 0
            return
        }

        val stagger = VideoFeedStyles.dpToPx(context, PINNED_STAGGER_DP) * floatingCount()
        // Position in right 2/3 of screen (avoids sidebar/menu area), staggered diagonally
        val rightAreaStart = screenWidth / 3
        val rightAreaWidth = screenWidth - rightAreaStart
        lp.gravity = Gravity.NO_GRAVITY
        lp.leftMargin = rightAreaStart + (rightAreaWidth - lp.width) / 2 + stagger
        lp.topMargin = (screenHeight - lp.height) / 2 + stagger
        lp.rightMargin = 0
        lp.bottomMargin = 0
    }

    /**
     * Apply position based on display method and slot index.
     * Used as fallback when coordinator panel is unavailable.
     */
    private fun applyPosition(
        lp: FrameLayout.LayoutParams,
        displayMethod: String,
        size: String,
        slotIndex: Int,
        totalCards: Int = slotIndex + 1
    ) {
        val s = VideoFeedStyles
        val cardHeight = s.cardHeightPx(context, size)
        val cardWidth = s.cardWidthPx(context, size)

        when (displayMethod) {
            "notification" -> {
                val gap = s.dpToPx(context, s.NOTIFICATION_HORIZONTAL_GAP_DP)
                val totalWidth = totalCards * cardWidth + (totalCards - 1) * gap
                val screenWidth = context.resources.displayMetrics.widthPixels
                val startX = (screenWidth - totalWidth) / 2
                val bottomMargin = s.dpToPx(context, s.NOTIFICATION_BOTTOM_MARGIN_DP)

                lp.gravity = Gravity.NO_GRAVITY
                lp.width = cardWidth
                lp.leftMargin = startX + slotIndex * (cardWidth + gap)
                lp.topMargin = context.resources.displayMetrics.heightPixels - cardHeight - bottomMargin
                lp.rightMargin = 0
                lp.bottomMargin = 0
            }
            else -> {
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                val feedAreaWidth = screenWidth / 3
                val feedAreaStart = screenWidth - feedAreaWidth
                val margin = s.dpToPx(context, s.SIDEBAR_PANEL_MARGIN_DP)
                val gap = s.dpToPx(context, s.SIDEBAR_CARD_GAP_DP)
                val effectiveWidth = minOf(cardWidth, feedAreaWidth - 2 * margin)

                val totalHeight = totalCards * cardHeight + (totalCards - 1) * gap
                val startY = maxOf(margin, (screenHeight - totalHeight) / 2)

                lp.gravity = Gravity.NO_GRAVITY
                lp.width = effectiveWidth
                lp.leftMargin = feedAreaStart + (feedAreaWidth - effectiveWidth) / 2
                lp.topMargin = startY + slotIndex * (cardHeight + gap)
                lp.rightMargin = 0
                lp.bottomMargin = 0
            }
        }
    }

    private fun updateCardLayoutParams(ruleId: String) {
        val card = activeFeedCards[ruleId] ?: return
        card.layoutParams = card.cardLayoutParams
    }

    // ── Trigger State Tracking ───────────────────────────────────────

    fun updateTriggerState(entityId: String, newState: String) {
        triggerEntityStates[entityId] = newState
    }

    // ── Auto-Dismiss Timers ──────────────────────────────────────────

    private fun startAutoDismissTimer(ruleId: String, seconds: Int) {
        cancelAutoDismissTimer(ruleId)
        val runnable = Runnable {
            val data = activeFeedData[ruleId]
            if (data?.isPinned == true || data?.isFloating == true) {
                Log.i(TAG, "Auto-dismiss skipped for $ruleId — feed is pinned or floating")
                return@Runnable
            }
            if (data != null && continueWhileActive && data.triggerEntityId.isNotBlank()) {
                val currentState = triggerEntityStates[data.triggerEntityId]
                val triggerState = "on"
                if (currentState == triggerState) {
                    Log.i(TAG, "Continue-while-active: trigger ${data.triggerEntityId} still $currentState — resetting timer for $ruleId")
                    startAutoDismissTimer(ruleId, autoDismissSeconds)
                    return@Runnable
                }
                Log.i(TAG, "Continue-while-active: trigger ${data.triggerEntityId} now $currentState — dismissing $ruleId")
            }
            Log.i(TAG, "Auto-dismiss timer fired for: $ruleId")
            dismissFeed(ruleId)
        }
        autoDismissHandlers[ruleId] = runnable
        handler.postDelayed(runnable, seconds * 1000L)
    }

    private fun resetAutoDismissTimer(ruleId: String, seconds: Int) {
        if (seconds > 0) {
            startAutoDismissTimer(ruleId, seconds)
        }
    }

    private fun cancelAutoDismissTimer(ruleId: String) {
        autoDismissHandlers.remove(ruleId)?.let { handler.removeCallbacks(it) }
    }

    // ── Strip Mode ──────────────────────────────────────────────────────

    /** Toggle the strip visibility. Called from sidebar camera button. */
    fun toggleStrip() {
        if (isStripVisible) {
            hideStrip()
            releaseDpad()
        } else {
            showStrip()
            engageDpad()
        }
    }

    // ── TV d-pad control (strip + Frigate playback) ──────────────────

    /** Engage d-pad capture in STRIP zone. TV devices only — touch/desktop
     *  sessions never grab the remote. No-op if not a TV device. */
    private fun engageDpad() {
        if (!(com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(context) ||
                com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV())) return
        if (dpadController == null) dpadController = VideoFeedDpadController(dpadHost)
        dpadCaptureActive = true
        dpadController?.startInStrip()
    }

    /** Release d-pad capture (returns control to the dashboard). */
    fun releaseDpad() {
        dpadCaptureActive = false
        dpadController?.release()
        stripRenderer?.highlightStripCard(null)
    }

    /** Whether the video overlay is currently capturing d-pad input. */
    fun isCapturingDpad(): Boolean = dpadCaptureActive

    /** Route a d-pad/center KeyEvent. Returns true if consumed. */
    fun handleDpadKey(event: KeyEvent): Boolean {
        if (!dpadCaptureActive) return false
        return dpadController?.handleKey(event) ?: false
    }

    /** Route a BACK press. Returns true if consumed. */
    fun handleDpadBack(): Boolean {
        if (!dpadCaptureActive) return false
        return dpadController?.handleBack() ?: false
    }

    private val dpadHost = object : VideoFeedDpadController.Host {
        override fun dpadSelectableRuleIds(): List<String> =
            stripRenderer?.getSelectableRuleIds() ?: emptyList()

        override fun dpadHighlightStripCard(ruleId: String?) {
            stripRenderer?.highlightStripCard(ruleId)
        }

        override fun dpadOpenFeed(ruleId: String): Boolean {
            val rule = prefsProvider?.invoke()?.getRules()?.find { it.optString("id") == ruleId }
            val isFrigate = rule != null && VideoFeedData.fromJson(rule).isFrigateCamera
            // onStripFeedTapped opens the focal drawer (and wires playback for
            // Frigate cameras); openFrigatePlayback then finds the card there.
            onStripFeedTapped(ruleId)
            if (isFrigate) openFrigatePlayback(ruleId, tvMode = true)
            return isFrigate
        }

        override fun dpadActivePlayback(): FrigatePlaybackController? = activePlaybackController

        override fun dpadExitPlaybackToStrip() {
            // close() reopens the strip via onReopenStrip; showStripInternal's
            // tail re-applies the highlight via onStripRebuilt().
            activePlaybackController?.close()
        }

        override fun dpadCloseFocalToStrip() {
            closeDrawer()
        }

        override fun dpadCloseStrip() {
            hideStrip()
            releaseDpad()
        }

        override fun dpadStripVisible(): Boolean = isStripVisible
    }

    /** Show the bottom strip with all subscribed feeds.
     *  Syncs feed rules from HA in background to pick up deletions/additions
     *  from other devices. Strip shows immediately with cached rules, then
     *  rebuilds if the HA sync finds changes. */
    fun showStrip() {
        if (isStripVisible) return

        // Show immediately with cached rules
        val prefs = prefsProvider?.invoke()
        val cachedRuleCount = prefs?.getEnabledRules()?.size ?: 0
        showStripInternal()

        // Sync from HA in background — if rule count changed, rebuild the strip
        prefs?.pullFeedsFromHa { success ->
            if (!success) return@pullFeedsFromHa
            val freshRuleCount = prefs.getEnabledRules().size
            if (freshRuleCount != cachedRuleCount) {
                Log.i(TAG, "Feed rules changed after HA sync ($cachedRuleCount → $freshRuleCount), rebuilding strip")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (isStripVisible) {
                        hideStrip()
                        showStripInternal()
                    }
                }
            }
        }
    }

    /** Internal: render the strip using whatever rules are currently in prefs. */
    private fun showStripInternal() {
        if (isStripVisible) return

        val prefs = prefsProvider?.invoke()
        val rules = prefs?.getEnabledRules() ?: emptyList()
        if (rules.isEmpty()) {
            Log.i(TAG, "showStrip: no enabled rules, nothing to show")
            return
        }

        Log.i(TAG, "showStrip: ${rules.size} feeds")

        // Build strip container
        val sidebarWidth = VideoFeedStyles.dpToPx(context, VideoFeedStyles.STRIP_SIDE_PADDING_DP)
        val screenWidth = context.resources.displayMetrics.widthPixels

        // Count offline feeds
        val offlineCount = rules.count { !it.optBoolean("available", true) }

        val renderer = VideoFeedStripRenderer(
            context = context,
            isPaused = isPaused,
            isAlertsMuted = isAlertsMuted,
            offlineCount = offlineCount,
            onClose = { hideStrip() },
            onMaximize = { /* no-op placeholder */ },
            onTogglePause = { paused ->
                if (paused) pause() else resume()
            },
            onToggleAlertsMuted = { muted ->
                isAlertsMuted = muted
            },
            onToggleShowOffline = { show ->
                // TODO: filter strip cards by availability
                Log.i(TAG, "Show offline feeds: $show")
            },
            onFeedTapped = { ruleId -> onStripFeedTapped(ruleId) },
            onFeedDragUp = { ruleId, card -> onStripFeedDragUp(ruleId, card) }
        )

        val drawer = VideoFeedDrawer(
            context = context,
            onClose = { closeDrawer() },
            onMaximize = { ruleId -> /* no-op placeholder */ }
        )

        val stripHeightPx = VideoFeedStyles.dpToPx(context, renderer.heightDp)
        val maxStripWidth = screenWidth - sidebarWidth
        // Compute the leftMargin so the strip is centered within the dashboard
        // area (screenWidth minus left sidebar). Call via the helper below
        // whenever the strip width changes so alignment stays correct on
        // grow/shrink (e.g. offline-toggle add/remove of cards).
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                maxStripWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // START (not CENTER_HORIZONTAL): we compute the offset manually
                // via leftMargin so the strip centers within the dashboard area
                // (screenWidth - sidebarWidth). leftMargin is updated every
                // time width changes via the helper below.
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = sidebarWidth
            }
            clipChildren = false
        }
        // Centers the container's leftMargin inside the dashboard area. Called
        // whenever the strip width changes so a narrow strip (few cards)
        // centers, while a maxed-out strip caps its right edge at the screen
        // edge (leftMargin == sidebarWidth when width == maxStripWidth).
        val applyStripMargin: (Int) -> Unit = { stripW ->
            (container.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val dashboardWidth = screenWidth - sidebarWidth
                val extra = (dashboardWidth - stripW).coerceAtLeast(0) / 2
                lp.leftMargin = sidebarWidth + extra
                container.layoutParams = lp
            }
        }

        // Inner layout: drawer (top) + strip (bottom)
        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
        }

        // Add drawer view (initially GONE)
        innerLayout.addView(drawer.view)

        // Strip content frame
        val sf = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, stripHeightPx
            )
        }
        renderer.render(sf)
        innerLayout.addView(sf)
        this.stripFrame = sf

        container.addView(innerLayout)

        // Create feed cards for the strip
        val baseUrl = haBaseUrlProvider().trimEnd('/')
        val token = haTokenProvider()

        // Show cards immediately with MJPEG, then upgrade to RTSP in background
        if (baseUrl.isBlank() || token.isBlank()) {
            Log.e(TAG, "showStrip: missing HA base URL or token")
        } else {
            val rendererRef = renderer

            // Diagnostic: count of rules received from the strip trigger — compare
            // to the "Strip build complete" count below. If they differ, a rule
            // was filtered out; if cards are missing on-screen but counts match,
            // the renderer is dropping views. Helps track the partial-load bug.
            Log.i(TAG, "📊 Strip build start: ${rules.size} rule(s) to render")
            var cardsBuilt = 0
            var cardsSkippedNoData = 0
            // Load last-known availability so cards that were offline on the
            // previous scan start hidden — avoids the flash where every card
            // briefly shows loading before the background scan resolves.
            val lastAvailability = prefsProvider?.invoke()?.getLastAvailability() ?: emptyMap()

            // Step 1: Create cards immediately with MJPEG (no blocking)
            for (rule in rules) {
                val baseData = VideoFeedData.fromJson(rule)
                // Start each card with its last-known availability from prefs.
                // Unknown rules default to true (optimistic) so genuinely new
                // feeds still surface before their first scan. Background
                // scan updates cards on changes — prevents the flash of
                // offline cards showing "loading" at strip open.
                val effectiveAvail = lastAvailability[baseData.ruleId] ?: true
                val data = baseData.copy(isAvailable = effectiveAvail)

                val widthParam = if (data.resolution > 0) "&width=${data.resolution}" else ""
                val streamUrl = when (data.streamSourceType) {
                    "rtsp" -> {
                        val encodedSource = java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")
                        "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam&source=$encodedSource"
                    }
                    "go2rtc" -> {
                        "$baseUrl/api/go2rtc/stream.mjpeg?src=${java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")}"
                    }
                    else -> {
                        "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam"
                    }
                }
                val snapshotUrl = if (data.cameraEntityId.isNotBlank())
                    "$baseUrl${ApiPaths.HA}/stream/snapshot/${data.cameraEntityId}"
                else ""

                val card = VideoFeedCard(
                    context = context,
                    overlayContainer = overlayContainer,
                    data = data,
                    size = "small",
                    displayMethod = "strip",
                    streamUrl = streamUrl,
                    snapshotUrl = snapshotUrl,
                    targetFps = data.fps,
                    authTokenProvider = haTokenProvider,
                    onTokenRefreshNeeded = onTokenRefreshNeeded,
                    onMaximize = { /* handled by drawer */ },
                    onNormalize = { /* not applicable */ },
                    onDismiss = { /* not applicable in strip mode */ },
                    onPinChanged = null,
                    initialShowOverlayControls = false
                )
                visibilityGate?.register(
                    card,
                    com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
                )
                rendererRef.addFeedCard(card, data.ruleId, data.isAvailable)
                cardsBuilt++
                Log.d(TAG, "📊 Strip: added card #$cardsBuilt ruleId=${data.ruleId} label='${data.displayLabel}' entity='${data.cameraEntityId}'")
                // Dump the raw rule JSON for rules with blank entityId — helps
                // diagnose why the local cache lost the field.
                if (data.cameraEntityId.isBlank()) {
                    Log.w(TAG, "📊 Strip: blank entityId for ruleId=${data.ruleId}; raw rule JSON: $rule")
                }
            }
            Log.i(TAG, "📊 Strip build complete: $cardsBuilt card(s) rendered, $cardsSkippedNoData skipped (from ${rules.size} rules)")

            // Step 2: Resolve RTSP URLs in background for focal use + offline detection.
            // Strip cards stay on MJPEG — only focal/drawer cards use ExoPlayer RTSP.
            // Also schedule periodic re-runs to catch feeds that come online later.
            scanFeedAvailability(rules, rendererRef, baseUrl, isInitial = true)

            // Set width now that cards exist, then animate entrance
            val idealWidth = minOf(rendererRef.calculateIdealWidthPx(), maxStripWidth)
            (container.layoutParams as? FrameLayout.LayoutParams)?.width = idealWidth
            applyStripMargin(idealWidth)
            container.translationY = stripHeightPx.toFloat()
            container.animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // Add container to overlay immediately, cards are already created
        overlayContainer.addView(container)
        // Register strip wrapper with the gate — force-hides on OFF mode
        // (login screen / logout). Belt-and-suspenders alongside the
        // individual card registrations: hiding the wrapper alone would
        // hide every card inside, but registering both keeps the gate's
        // bookkeeping correct if the strip is destroyed asymmetrically.
        visibilityGate?.register(
            container,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        stripContainer = container
        stripRenderer = renderer
        stripDrawer = drawer
        isStripVisible = true
        displayState = FeedDisplayState.Strip

        // If d-pad capture is on (HA-sync rebuild, or returning from playback),
        // re-apply the feed highlight to the freshly-built strip.
        if (dpadCaptureActive) handler.post { dpadController?.onStripRebuilt() }

        // Wire resize callback — adjusts container width when offline feeds are toggled
        renderer.onRequestResize = {
            val idealW = renderer.calculateIdealWidthPx()
            val maxW = screenWidth - sidebarWidth
            val newW = minOf(idealW, maxW)
            (container.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = newW
                container.layoutParams = lp
            }
            applyStripMargin(newW)
        }

        // Wire drag-up-started: create a bitmap ghost in overlayContainer so the card
        // appears to float above the strip during drag. The real card stays in the strip
        // hierarchy to keep the touch chain intact (reparenting breaks touch dispatch).
        renderer.onFeedDragUpStarted = { _, card ->
            val loc = IntArray(2)
            card.getLocationOnScreen(loc)
            val containerLoc = IntArray(2)
            overlayContainer.getLocationOnScreen(containerLoc)

            // Snapshot the card as a bitmap
            val ghost = ImageView(context).apply {
                val bmp = android.graphics.Bitmap.createBitmap(card.width, card.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                card.draw(canvas)
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            val lp = FrameLayout.LayoutParams(card.width, card.height).apply {
                gravity = Gravity.NO_GRAVITY
                leftMargin = loc[0] - containerLoc[0]
                topMargin = loc[1] - containerLoc[1]
            }
            overlayContainer.addView(ghost, lp)
            card.dragGhost = ghost
        }

        onBottomInsetDp?.invoke(renderer.heightDp)
        Log.i(TAG, "Strip shown with ${rules.size} feeds")
    }

    /** Hide the strip with slide-down animation. */
    /**
     * Run one pass of the strip-feed availability resolve, then reschedule
     * itself to run again in [AVAILABILITY_RESCAN_INTERVAL_MS] so feeds that
     * come online/offline mid-session are picked up.
     *
     * The initial call (from showStrip) also populates [resolvedRtspUrls] for
     * focal RTSP upgrade. Subsequent calls only refresh availability — RTSP
     * URL updates after the first don't propagate back to existing cards (not
     * needed, they're already live via MJPEG).
     *
     * Cancelled in [hideStrip] so the loop doesn't outlive the strip.
     */
    /**
     * Open a short TCP connection to the host:port of an rtsp URL to see if
     * the camera is reachable. 2s timeout — enough for local-network
     * cameras, short enough not to delay the strip UI. Returns true on
     * successful connect, false on timeout / host unreachable / malformed URL.
     */
    private fun probeRtspReachable(rtspUrl: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(rtspUrl)
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else 554  // RTSP default port
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 2000)
                Log.d(TAG, "📡 RTSP probe $host:$port: reachable")
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "📡 RTSP probe failed for $rtspUrl: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun scanFeedAvailability(
        rules: List<org.json.JSONObject>,
        renderer: VideoFeedStripRenderer,
        baseUrl: String,
        isInitial: Boolean,
    ) {
        // Intentionally no isStripVisible gate here — the initial call fires
        // from showStrip before isStripVisible is set to true. The post-back
        // to the main thread below guards against the strip being closed
        // between the network work and the UI update.
        Thread {
            val rtspUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
            val liveAvailability = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            // Per-rule probe results for rtsp/go2rtc sources (host:port TCP probe).
            // Keyed by ruleId because the rtsp URL isn't a stable identifier.
            val rtspProbeResults = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val entityIds = rules.mapNotNull { rule ->
                val id = rule.optString("cameraEntityId", "")
                if (id.isNotBlank()) id else null
            }.distinct()

            // Collect direct-rtsp / go2rtc rules to probe by TCP.
            // go2rtc streams don't have a URL we can probe directly (they
            // reference a named stream registered on the Frigate host), so
            // we currently only probe direct rtsp:// URLs. go2rtc sources
            // are assumed online — a blank streamSourceUrl is the only
            // signal we have and that's already handled as offline.
            val rtspToProbe = mutableListOf<Pair<String, String>>()  // (ruleId, rtsp url)
            for (rule in rules) {
                val ruleId = rule.optString("ruleId", rule.optString("id", ""))
                val sourceType = rule.optString("streamSourceType", "entity")
                val url = rule.optString("streamSourceUrl", "")
                if (ruleId.isNotBlank() && sourceType == "rtsp" && url.isNotBlank()) {
                    rtspToProbe.add(ruleId to url)
                }
            }

            val latch = java.util.concurrent.CountDownLatch(entityIds.size + rtspToProbe.size)
            for (entityId in entityIds) {
                Thread {
                    try {
                        val freshToken = haTokenProvider()
                        if (freshToken.isBlank()) return@Thread
                        // check_only=1: get availability without auto-registering new streams.
                        val resolveUrl = "$baseUrl${ApiPaths.HA}/stream/resolve/$entityId?check_only=1"
                        val request = okhttp3.Request.Builder()
                            .url(resolveUrl)
                            .header("Authorization", "Bearer $freshToken")
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                val json = org.json.JSONObject(body)
                                val rtspUrl = json.optString("rtsp_url", "")
                                if (rtspUrl.isNotBlank() && rtspUrl != "null") {
                                    rtspUrls[entityId] = rtspUrl
                                }
                                if (json.has("available")) {
                                    liveAvailability[entityId] = json.optBoolean("available", true)
                                }
                                Log.d(
                                    TAG,
                                    "📡 Resolve $entityId: code=${response.code} body=${body.take(200)}",
                                )
                            } else if (response.code == 404) {
                                liveAvailability[entityId] = false
                                Log.w(TAG, "📡 Resolve $entityId: 404 (entity not found)")
                            } else {
                                Log.w(
                                    TAG,
                                    "📡 Resolve $entityId: FAILED code=${response.code}",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "📡 Resolve $entityId: exception ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }.start()
            }

            // Parallel TCP probes for rtsp:// sources — tells us whether the
            // camera host is reachable on its rtsp port. Can't do this for
            // entity-backed feeds (no direct URL) but rtsp cameras (Onn stick,
            // etc.) expose a host:port we can connect to with a short timeout.
            for ((ruleId, rawUrl) in rtspToProbe) {
                Thread {
                    rtspProbeResults[ruleId] = probeRtspReachable(rawUrl)
                    latch.countDown()
                }.start()
            }

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (isInitial) {
                Log.i(TAG, "Resolved ${rtspUrls.size}/${entityIds.size} RTSP URLs (for focal use)")
                resolvedRtspUrls = rtspUrls.toMap()
            } else {
                Log.d(TAG, "Periodic rescan: ${rtspUrls.size}/${entityIds.size} resolved, ${liveAvailability.count { it.value }} online")
            }

            handler.post {
                if (!isStripVisible) return@post
                val snapshot = mutableMapOf<String, Boolean>()
                for (rule in rules) {
                    val entityId = rule.optString("cameraEntityId", "")
                    val ruleId = rule.optString("ruleId", rule.optString("id", ""))
                    if (ruleId.isBlank()) continue
                    // Non-entity sources (rtsp / go2rtc) legitimately have no
                    // cameraEntityId. Can't check availability via the HA
                    // resolve endpoint. For rtsp sources we do a TCP probe to
                    // the camera's host:port — catches unplugged/unreachable
                    // cameras like the Onn stick. go2rtc streams don't expose
                    // a direct URL we can probe, so they stay "online" as
                    // long as streamSourceUrl is non-blank.
                    val sourceType = rule.optString("streamSourceType", "entity")
                    val streamSourceUrl = rule.optString("streamSourceUrl", "")
                    val isOffline = when {
                        sourceType == "rtsp" -> {
                            streamSourceUrl.isBlank() ||
                                rtspProbeResults[ruleId] == false
                        }
                        sourceType == "go2rtc" -> streamSourceUrl.isBlank()
                        entityId.isBlank() -> true
                        liveAvailability[entityId] == false -> true
                        !liveAvailability.containsKey(entityId) -> true
                        else -> false
                    }
                    snapshot[ruleId] = !isOffline
                    renderer.updateFeedAvailability(ruleId, !isOffline)
                }
                // Persist the snapshot so the next strip open starts with the
                // correct offline/online state (avoids flash-of-loading on
                // cards we already know to be offline).
                prefsProvider?.invoke()?.setLastAvailability(snapshot)
                renderer.onRequestResize?.invoke()

                // Schedule the next rescan. Cancel any pending one first.
                availabilityRescanRunnable?.let { handler.removeCallbacks(it) }
                val next = Runnable { scanFeedAvailability(rules, renderer, baseUrl, isInitial = false) }
                availabilityRescanRunnable = next
                handler.postDelayed(next, AVAILABILITY_RESCAN_INTERVAL_MS)
            }
        }.start()
    }

    /** Synchronous strip/drawer teardown for replace-in-place paths (voice drawer,
     *  playback-over-strip). Mirrors hideStrip()'s end-of-animation work but runs it
     *  NOW, so the caller can immediately assign fresh stripContainer/stripDrawer refs
     *  without the async end-listener nulling them from under it. No pinned-card
     *  rescue: the caller is about to put a new focal card in the same spot. */
    private fun teardownStripImmediately() {
        val container = stripContainer ?: return
        Log.i(TAG, "teardownStripImmediately (replacing existing drawer/strip)")
        availabilityRescanRunnable?.let { handler.removeCallbacks(it) }
        availabilityRescanRunnable = null
        container.animate().setListener(null).cancel()
        stripDrawer?.destroy()
        stripDimOverlay?.let { overlayContainer.removeView(it) }
        stripDimOverlay = null
        stripRenderer?.releaseAll()
        visibilityGate?.unregister(container)
        overlayContainer.removeView(container)
        stripContainer = null
        stripRenderer = null
        stripDrawer = null
        stripFrame = null
        isStripVisible = false
        onBottomInsetDp?.invoke(0)
    }

    fun hideStrip() {
        if (!isStripVisible) return
        // Stop the availability-rescan loop so it doesn't run against a closed strip.
        availabilityRescanRunnable?.let { handler.removeCallbacks(it) }
        availabilityRescanRunnable = null

        val container = stripContainer ?: return
        Log.i(TAG, "hideStrip")

        // If the drawer's focal feed is pinned, rescue it as a floating card
        val pinnedPair = stripDrawer?.detachPinnedCard()
        if (pinnedPair != null) {
            val (card, ruleId) = pinnedPair
            Log.i(TAG, "Rescuing pinned drawer feed: $ruleId")
            val data = VideoFeedData.fromJson(
                prefsProvider?.invoke()?.getRules()?.find { it.optString("id") == ruleId }
                    ?: org.json.JSONObject()
            ).copy(isPinned = true, isFloating = true)

            // Position centered on screen
            val lp = card.cardLayoutParams
            applyInitialPinnedPosition(lp)
            overlayContainer.addView(card, lp)

            // Make it fully interactive floating card
            card.isDraggable = true
            card.isResizable = true
            card.tapToMaximize = false
            card.canDragToUnpin = false
            card.canDragUpToFloat = false

            // Track as active floating feed
            activeFeedCards[ruleId] = card
            activeFeedData[ruleId] = data
            // Phase 1 instrumentation: rescued drawer feed is now floating
            placements.record(ruleId, CardPlacement.Floating(lp))
            verifyPlacement(ruleId)
            onVisibilityChanged?.invoke(activeFeedCards.size)
        }

        // Close remaining drawer and dim overlay
        stripDrawer?.destroy()
        stripDimOverlay?.let { overlayContainer.removeView(it) }
        stripDimOverlay = null

        container.animate()
            .translationY(container.height.toFloat())
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stripRenderer?.releaseAll()
                    visibilityGate?.unregister(container)
                    overlayContainer.removeView(container)
                    stripContainer = null
                    stripRenderer = null
                    stripDrawer = null
                    stripFrame = null
                    isStripVisible = false
                    displayState = if (activeFeedCards.isEmpty()) FeedDisplayState.Idle else displayState
                    onBottomInsetDp?.invoke(0)
                    container.animate().setListener(null)
                }
            })
            .start()
    }

    /** Handle tap on a feed card in the strip — open/swap in drawer. */
    private fun onStripFeedTapped(ruleId: String) {
        val drawer = stripDrawer ?: return
        val prefs = prefsProvider?.invoke() ?: return
        val rule = prefs.getRules().find { it.optString("id") == ruleId } ?: return
        val baseData = VideoFeedData.fromJson(rule)

        Log.i(TAG, "Strip feed tapped: $ruleId")

        val baseUrl = haBaseUrlProvider().trimEnd('/')
        val token = haTokenProvider()
        if (baseUrl.isBlank() || token.isBlank()) return

        // Use cached RTSP URL if available, otherwise focal card starts with MJPEG
        // and upgrades to RTSP after on-demand resolve completes
        val cachedRtspUrl = resolvedRtspUrls[baseData.cameraEntityId] ?: ""
        val data = if (cachedRtspUrl.isNotBlank() && baseData.rtspUrl.isBlank()) {
            baseData.copy(rtspUrl = cachedRtspUrl)
        } else baseData

        val widthParam = if (data.resolution > 0) "&width=${data.resolution}" else ""
        val streamUrl = when (data.streamSourceType) {
            "rtsp" -> {
                val encodedSource = java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")
                "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam&source=$encodedSource"
            }
            "go2rtc" -> {
                "$baseUrl/api/go2rtc/stream.mjpeg?src=${java.net.URLEncoder.encode(data.streamSourceUrl, "UTF-8")}"
            }
            else -> {
                "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam"
            }
        }

        val card = VideoFeedCard(
            context = context,
            overlayContainer = overlayContainer,
            data = data,
            size = "large",
            displayMethod = "strip",
            streamUrl = streamUrl,
            targetFps = data.fps,
            authTokenProvider = haTokenProvider,
            onTokenRefreshNeeded = onTokenRefreshNeeded,
            onMaximize = {
                if (activeFeedCards.containsKey(ruleId)) {
                    // Already tracked as floating — maximize directly
                    maximizeFeed(ruleId)
                } else {
                    // Focal feed in drawer — maximize in-place, track for restore
                    maximizeDrawerFeed(ruleId)
                }
            },
            onNormalize = {
                if (activeFeedCards.containsKey(ruleId)) {
                    // Route based on previous placement recorded by the coordinator.
                    // DrawerFocal → put card back in the drawer.
                    // Floating → restore saved floating lp.
                    // Anything else → fall through to normalizeFeed's legacy path.
                    when (placements.previousPlacementFor(ruleId)) {
                        is CardPlacement.DrawerFocal -> normalizeDrawerFeed(ruleId)
                        else -> normalizeFeed(ruleId)
                    }
                }
            },
            onDismiss = {
                // If tracked as floating, dismiss normally; else close the drawer
                if (activeFeedCards.containsKey(ruleId)) {
                    dismissFeed(ruleId)
                } else {
                    closeDrawer()
                }
            },
            onPinChanged = { pinned ->
                Log.i(TAG, "Drawer feed pin changed: $ruleId → pinned=$pinned")
            }
        )
        visibilityGate?.register(
            card,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        // Wire Frigate playback button for drawer cards
        if (data.isFrigateCamera) {
            card.onPlaybackRequested = { openFrigatePlayback(ruleId) }
        }

        drawer.showFeed(card, ruleId)
        stripRenderer?.setActiveFeed(ruleId)
        // Phase 1+ instrumentation: drawer focal feed. Required so that
        // restoreFromPlayback can route back to the drawer (otherwise
        // lastNonScreensaver is null and the coordinator takes the
        // fall-through branch, stranding the card in overlayContainer).
        placements.record(ruleId, CardPlacement.DrawerFocal)

        // On-demand RTSP resolve for focal card — triggers relay registration
        // only when the user actually opens a feed (no check_only flag)
        triggerOnDemandRtspUpgrade(card, baseData.cameraEntityId, cachedRtspUrl, baseUrl)

        // Show dim overlay behind drawer/strip
        showStripDimOverlay()

        // Update strip container height to include drawer
        updateStripContainerHeight()
    }

    /** Handle drag-up on a strip feed card — detach to floating. */
    private fun onStripFeedDragUp(ruleId: String, card: VideoFeedCard) {
        Log.i(TAG, "Strip feed drag-up to float: $ruleId")

        // If this ruleId is already active (e.g., previously dragged up, or
        // currently showing as a notification alert), dismiss the old card
        // first. Otherwise activeFeedCards[ruleId] = newCard below would
        // silently replace the map entry, orphaning the prior card's view
        // in overlayContainer — exactly the stuck-card bug.
        if (activeFeedCards.containsKey(ruleId)) {
            Log.i(TAG, "  ruleId already active — dismissing prior card before drag-up")
            dismissFeed(ruleId)
        }

        // Use the ghost's final screen position (where the user released)
        val containerLoc = IntArray(2)
        overlayContainer.getLocationOnScreen(containerLoc)
        val loc = intArrayOf(card.dragReleaseScreenX, card.dragReleaseScreenY)

        // Keep the strip card in place — don't remove it. The strip card already
        // snapped back via animate() before this callback fires.

        // Create a new card with real callbacks for floating mode
        val prefs = prefsProvider?.invoke() ?: return
        val rule = prefs.getRules().find { it.optString("id") == ruleId } ?: return
        val data = VideoFeedData.fromJson(rule)
        val baseUrl = haBaseUrlProvider().trimEnd('/')
        if (baseUrl.isBlank() || haTokenProvider().isBlank()) return

        val widthParam = if (data.resolution > 0) "&width=${data.resolution}" else ""
        val streamUrl = when (data.streamSourceType) {
            "rtsp" -> "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam&source=${URLEncoder.encode(data.streamSourceUrl, "UTF-8")}"
            "go2rtc" -> "$baseUrl/api/go2rtc/stream.mjpeg?src=${URLEncoder.encode(data.streamSourceUrl, "UTF-8")}"
            else -> "$baseUrl${ApiPaths.HA}/stream/mjpeg/${data.cameraEntityId}?fps=${data.fps}&quality=${data.quality}$widthParam"
        }

        val floatingData = data.copy(isPinned = true, isFloating = true)
        // Drag-up from strip lands at medium size — strip-card size was too
        // small to be useful, and feedSize (used by triggered alerts) is
        // often too large. Medium is a consistent, resizable starting point.
        val newCard = VideoFeedCard(
            context = context,
            overlayContainer = overlayContainer,
            data = floatingData,
            size = "medium",
            displayMethod = "sidebar",
            streamUrl = streamUrl,
            targetFps = data.fps,
            authTokenProvider = haTokenProvider,
            onTokenRefreshNeeded = onTokenRefreshNeeded,
            onMaximize = { maximizeFeed(ruleId) },
            onNormalize = { normalizeFeed(ruleId) },
            onDismiss = { dismissFeed(ruleId) },
            onPinChanged = { pinned -> onPinChanged(ruleId, pinned) }
        )
        visibilityGate?.register(
            newCard,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        // Wire Frigate playback button
        if (data.isFrigateCamera) {
            newCard.onPlaybackRequested = { openFrigatePlayback(ruleId) }
        }

        // Position at the drag release location
        val lp = newCard.cardLayoutParams
        lp.gravity = Gravity.NO_GRAVITY
        lp.leftMargin = loc[0] - containerLoc[0]
        lp.topMargin = loc[1] - containerLoc[1]
        lp.rightMargin = 0
        lp.bottomMargin = 0
        overlayContainer.addView(newCard, lp)

        // Track as active floating feed
        activeFeedCards[ruleId] = newCard
        activeFeedData[ruleId] = floatingData
        onVisibilityChanged?.invoke(activeFeedCards.size)

        // Make free-floating with full controls.
        // tapToMaximize = true so the expand button shows on the floated card —
        // matches CardPlacement.Floating.interactivity. Before this fix it was
        // false and the user had no way to maximize a drag-up-to-float card.
        newCard.isDraggable = true
        newCard.isResizable = true
        newCard.tapToMaximize = true
        newCard.canDragToUnpin = false
        newCard.canDragUpToFloat = false
        newCard.updatePinned(true)

        // Record the placement so the coordinator knows this card is Floating.
        // Without this record, a later maximize->normalize cycle finds no
        // Floating memory in lastNonScreensaver, falls through the legacy
        // restore path, and positions the card at top-left with
        // cardLayoutParams defaults instead of the lp we set above.
        placements.record(ruleId, CardPlacement.Floating(lp))
    }

    /** Close the drawer (keep strip visible). */
    private fun closeDrawer() {
        stripDrawer?.close()
        stripRenderer?.setActiveFeed(null)
        hideStripDimOverlay()
        // Height will update after animation completes
        handler.postDelayed({ updateStripContainerHeight() }, 300)
    }

    /** Maximize a focal feed from the drawer — move to overlayContainer full-screen, track for restore. */
    private fun maximizeDrawerFeed(ruleId: String) {
        val pair = stripDrawer?.detachCard() ?: return
        val (card, detachedRuleId) = pair
        Log.i(TAG, "Maximize drawer feed: $detachedRuleId")

        val data = VideoFeedData.fromJson(
            prefsProvider?.invoke()?.getRules()?.find { it.optString("id") == detachedRuleId }
                ?: org.json.JSONObject()
        ).copy(isPinned = false, isFloating = true, isMaximized = true)

        card.setMaximized()
        overlayContainer.addView(card, card.cardLayoutParams)
        activeFeedCards[detachedRuleId] = card
        activeFeedData[detachedRuleId] = data
        // Phase 3b: coordinator remembers DrawerFocal as the restore target.
        // No more preMaximizeParams (0,0) sentinel.
        placements.recordMaximizeFromDrawer(detachedRuleId)
        verifyPlacement(detachedRuleId)
        hideStripDimOverlay()
    }

    /** Restore a maximized drawer feed back into the drawer. */
    private fun normalizeDrawerFeed(ruleId: String) {
        val card = activeFeedCards[ruleId] ?: return
        Log.i(TAG, "Normalize drawer feed back to drawer: $ruleId")

        val handled = placements.transitionMaximizedToDrawer(ruleId) {
            // Detach from overlayContainer + drop maximized mode before handing to drawer.
            (card.parent as? ViewGroup)?.removeView(card)
            card.setNormal()

            val drawer = stripDrawer
            if (drawer != null && isStripVisible) {
                card.isDraggable = true
                card.isResizable = true
                card.tapToMaximize = false
                drawer.showFeed(card, ruleId)
                stripRenderer?.setActiveFeed(ruleId)
                showStripDimOverlay()
                // Drawer cards aren't tracked in activeFeedCards — remove.
                activeFeedCards.remove(ruleId)
                activeFeedData.remove(ruleId)
            } else {
                // Strip was closed while maximized — release.
                card.release()
                activeFeedCards.remove(ruleId)
                activeFeedData.remove(ruleId)
                placements.forget(ruleId)
            }
        }

        if (!handled) {
            // Fallback: coordinator had no DrawerFocal memory. Should be rare after
            // Phase 3b but keep the old legacy path for safety.
            Log.w(TAG, "normalizeDrawerFeed: coordinator had no DrawerFocal prev for $ruleId — using legacy release path")
            activeFeedCards.remove(ruleId)
            activeFeedData.remove(ruleId)
            preMaximizeParams.remove(ruleId)
            (card.parent as? ViewGroup)?.removeView(card)
            card.setNormal()
            card.release()
            placements.forget(ruleId)
        }
    }

    /**
     * Detach the drawer's focal feed and move it to overlayContainer as a floating card.
     * If maximize=true, also maximize it after detaching.
     */
    private fun drawerFeedToFloating(ruleId: String, maximize: Boolean = false) {
        val pair = stripDrawer?.detachCard()
        if (pair == null) {
            Log.w(TAG, "drawerFeedToFloating: could not detach card for $ruleId")
            return
        }

        val (card, detachedRuleId) = pair
        Log.i(TAG, "Drawer feed → floating: $detachedRuleId (maximize=$maximize)")

        val data = VideoFeedData.fromJson(
            prefsProvider?.invoke()?.getRules()?.find { it.optString("id") == detachedRuleId }
                ?: org.json.JSONObject()
        ).copy(isPinned = true, isFloating = true)

        // Position centered
        val lp = card.cardLayoutParams
        applyInitialPinnedPosition(lp)
        overlayContainer.addView(card, lp)

        // Make fully interactive
        card.isDraggable = true
        card.isResizable = true
        card.tapToMaximize = false
        card.canDragToUnpin = false
        card.canDragUpToFloat = false

        // Track as active floating feed
        activeFeedCards[detachedRuleId] = card
        activeFeedData[detachedRuleId] = data
        // Phase 1 instrumentation: drawer → floating
        placements.record(detachedRuleId, CardPlacement.Floating(lp))
        verifyPlacement(detachedRuleId)
        onVisibilityChanged?.invoke(activeFeedCards.size)

        // Close drawer UI
        closeDrawer()

        // Maximize after detaching if requested
        if (maximize) {
            maximizeFeed(detachedRuleId)
        }
    }

    /** Show a semi-transparent dim overlay behind the strip/drawer. Tapping it closes the drawer. */
    private fun showStripDimOverlay() {
        if (stripDimOverlay != null) return
        val overlay = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x60000000.toInt())  // Semi-transparent black
            isClickable = true
            setOnClickListener { closeDrawer() }
            alpha = 0f
        }
        // Add behind the strip container
        val stripIdx = overlayContainer.indexOfChild(stripContainer)
        if (stripIdx >= 0) {
            overlayContainer.addView(overlay, stripIdx)
        } else {
            overlayContainer.addView(overlay, 0)
        }
        overlay.animate().alpha(1f).setDuration(200).start()
        stripDimOverlay = overlay
    }

    /** Hide and remove the dim overlay. */
    private fun hideStripDimOverlay() {
        val overlay = stripDimOverlay ?: return
        overlay.animate().alpha(0f).setDuration(200)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    overlayContainer.removeView(overlay)
                    overlay.animate().setListener(null)
                }
            }).start()
        stripDimOverlay = null
    }

    /** Recalculate strip container height when drawer opens/closes. */
    private fun updateStripContainerHeight() {
        val renderer = stripRenderer ?: return
        val drawer = stripDrawer ?: return
        val stripHeightPx = VideoFeedStyles.dpToPx(context, renderer.heightDp)
        val drawerHeightPx = if (drawer.isOpen) VideoFeedStyles.dpToPx(context, drawer.heightDp) else 0
        val totalDp = renderer.heightDp + (if (drawer.isOpen) drawer.heightDp else 0)
        onBottomInsetDp?.invoke(totalDp)
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    val activeFeedCount: Int get() = activeFeedCards.size

    fun isActive(ruleId: String): Boolean = activeFeedCards.containsKey(ruleId)

    fun destroy() {
        Log.i(TAG, "destroy: cleaning up ${activeFeedCards.size} active feeds")
        // Clean up strip
        stripDrawer?.destroy()
        stripRenderer?.releaseAll()
        stripDimOverlay?.let { overlayContainer.removeView(it) }
        stripContainer?.let {
            visibilityGate?.unregister(it)
            overlayContainer.removeView(it)
        }
        stripContainer = null
        stripRenderer = null
        stripDrawer = null
        stripDimOverlay = null
        isStripVisible = false

        autoDismissHandlers.values.forEach { handler.removeCallbacks(it) }
        autoDismissHandlers.clear()
        activeFeedCards.values.forEach { card ->
            try {
                (card.parent as? ViewGroup)?.removeView(card)
                card.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
        activeFeedCards.clear()
        activeFeedData.clear()
        savedPinnedPositions.clear()
        displayState = FeedDisplayState.Idle
        inScrollMode = false
        dimOverlay.destroy()
    }

}

package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.videofeed.VideoFeedStyles

/**
 * A participant that can place cards on the screensaver overlay panel.
 * Implemented by VideoFeedOverlayManager, MusicPlayerOverlayManager, TimerOverlayManager.
 */
interface ScreensaverPanelParticipant {
    /** Unique identifier ("video_feed", "music", "timer") */
    val participantId: String

    /** Lower = higher on screen (timer=0, music=1, video_feed=2) */
    val participantPriority: Int

    /** Number of cards currently on the panel */
    fun getScreensaverCardCount(): Int

    /** All cards currently on the panel */
    fun getScreensaverCards(): List<View>

    /**
     * Called when total item count on panel changes.
     * Participant should minimize/expand cards based on count.
     * - count 1: centered, normal size
     * - count 2: stacked, normal size
     * - count 3+: music/timers minimize, video feeds stay normal
     */
    fun onPanelItemCountChanged(totalItems: Int)

    /** Called when the non-screensaver sidebar activates (video feed triggered). */
    fun onSidebarActivated() {}

    /** Called when the non-screensaver sidebar deactivates (all unpinned feeds dismissed or dim tapped). */
    fun onSidebarDeactivated() {}

    /** Whether this participant's cards go in the scroll container (true) or directly on the panel (false). */
    val isScrollable: Boolean get() = false
}

/**
 * Coordinates the right-1/3 screensaver overlay panel shared by
 * video feeds, music player, and timers.
 *
 * Manages:
 * - Panel visibility and black background
 * - Notifying ScreenDimmer to resize screensaver content
 * - Layout recalculation when cards are added/removed
 * - Item count notifications for minimize/expand rules
 */
class ScreensaverPanelCoordinator(
    private val context: Context,
    private val panel: FrameLayout
) {
    companion object {
        private const val TAG = "ScreensaverPanelCoord"
        private const val SS_LOG = "SS_PANEL"  // PersistentLog tag for panel lifecycle
        private const val MARGIN_DP = 8
        private const val GAP_DP = 12
        private const val SIDEBAR_BG_ALPHA = 0.5f
        private const val SCREENSAVER_BG_ALPHA = 0.40f
        private const val DISMISS_CLEARANCE_DP = 52
        private const val COMPACT_CORNER_RADIUS_DP = 16
        private const val SCROLL_THRESHOLD = 3  // Switch to scroll mode at this many scrollable cards
        private const val MENU_CLEARANCE_DP = 540  // Space to clear sidebar (280dp) + popout (~260dp)
    }

    private val participants = mutableListOf<ScreensaverPanelParticipant>()
    private var bgView: View? = null
    private var scrollView: ScrollView? = null
    private var scrollContent: LinearLayout? = null
    private var hierarchyListenerInstalled = false

    var isScreensaverActive: Boolean = false
        private set

    /** Whether the non-screensaver sidebar is active (video feed triggered) */
    var isSidebarActive: Boolean = false
        private set

    /** Whether the panel is showing a persistent post-trigger layout */
    var isPersistentLayoutActive: Boolean = false
        private set

    /** Current layout mode: "sidebar" (right column), "grid" (full screen), or "float" */
    var layoutMode: String = "sidebar"

    /** Feed size for calculating strip width */
    var feedSize: String = "medium"

    /** Whether the camera popout menu is open (grid shifts to preview mode) */
    var isMenuOpen: Boolean = false

    /** Whether the panel is active in any mode */
    val isPanelActive: Boolean get() = isScreensaverActive || isSidebarActive || isPersistentLayoutActive

    /** Called to resize screensaver content when panel shows/hides. Passes feed area width in pixels. */
    var onResizeScreensaver: ((active: Boolean, feedAreaWidth: Int) -> Unit)? = null

    /**
     * Narrow-portrait only: forward the bottom panel height (card zone) so the
     * photo slideshow view can shrink its foreground photos to fit the top area.
     * Pass 0 to restore full-height photos.
     */
    var onScreensaverBottomInset: ((bottomPanelHeightPx: Int) -> Unit)? = null

    /**
     * Narrow-portrait only: signal whether the photo is the ONLY thing showing
     * (no music card, no camera strip). When true, landscape photos should
     * fit-with-bars instead of crop, so the user sees the whole image centered.
     */
    var onScreensaverPhotoAlone: ((alone: Boolean) -> Unit)? = null

    // ── Registration ──────────────────────────────────────────────

    fun registerParticipant(participant: ScreensaverPanelParticipant) {
        if (participants.none { it.participantId == participant.participantId }) {
            participants.add(participant)
            participants.sortBy { it.participantPriority }
            Log.d(TAG, "Registered participant: ${participant.participantId}")
        }
    }

    // ── Screensaver State ─────────────────────────────────────────

    /**
     * Called when screensaver activates/deactivates.
     * Participants should call this indirectly — it's wired from MainActivity.
     */
    fun onScreensaverStateChanged(active: Boolean) {
        isScreensaverActive = active
        PersistentLog.info(SS_LOG, "Screensaver active=$active " +
            "(panelVisible=${panel.visibility == View.VISIBLE}, cards=${getTotalCardCount()})")
        if (active) {
            // Slightly transparent so photo's black bg shows through rounded corners
            bgView?.alpha = SCREENSAVER_BG_ALPHA
            // Self-heal: if a participant leaked the panel (left it visible with
            // no cards), tear it down now instead of re-shrinking the photo into
            // an empty strip. Guards the "photo stuck on the left, blank strip on
            // the right" bug across sleep/wake cycles.
            if (reconcilePanelState()) {
                // Stale panel torn down — photo already restored to full width.
            } else if (panel.visibility == View.VISIBLE) {
                // Panel genuinely has content. Resize screensaver content.
                // In narrow portrait we don't shrink the photo horizontally —
                // reset any prior shrink state instead, then relayout() will
                // cover the bottom portion with an opaque bgView band.
                if (isNarrowPortraitScreensaver()) {
                    onResizeScreensaver?.invoke(false, 0)
                } else {
                    onResizeScreensaver?.invoke(true, getFeedAreaWidth())
                }
                // Reposition cards against the (now screensaver-clamped) feed area.
                if (getTotalCardCount() > 0) relayout()
            }
            // Narrow-portrait with no cards = photo alone → fit mode.
            if (isNarrowPortraitScreensaver() && getTotalCardCount() == 0) {
                onScreensaverPhotoAlone?.invoke(true)
            }
        } else {
            onScreensaverDeactivated()
            onScreensaverPhotoAlone?.invoke(false)
        }
        // Activation is handled by individual participants migrating their cards
    }

    private fun onScreensaverDeactivated() {
        if (!isSidebarActive) {
            hidePanel()
        } else {
            // Returning to sidebar mode — restore semi-transparent bg
            bgView?.alpha = SIDEBAR_BG_ALPHA
            onResizeScreensaver?.invoke(false, 0)
        }
    }

    // ── Sidebar Mode ───────────────────────────────────────────────

    /**
     * Activate the sidebar panel for non-screensaver video feed display.
     * Shows the panel and notifies all participants to migrate their cards.
     */
    fun activateSidebar() {
        if (isSidebarActive) return
        Log.i(TAG, "Sidebar activated")
        PersistentLog.info(SS_LOG, "Sidebar activated")
        isSidebarActive = true
        showPanelIfNeeded()
        // Defer bgView alpha/sizing to relayout() — single PiP uses compact bg
        if (!isScreensaverActive) {
            bgView?.alpha = SIDEBAR_BG_ALPHA
        }
        for (p in participants) {
            p.onSidebarActivated()
        }
    }

    /**
     * Deactivate the sidebar panel.
     * Notifies participants to migrate cards back, then hides panel.
     */
    fun deactivateSidebar() {
        if (!isSidebarActive) return
        Log.i(TAG, "Sidebar deactivated")
        PersistentLog.info(SS_LOG, "Sidebar deactivated (cards=${getTotalCardCount()})")
        for (p in participants) {
            p.onSidebarDeactivated()
        }
        isSidebarActive = false
        // Tear the panel down whenever no cards remain — even if the screensaver
        // is active. Previously this was gated on !isScreensaverActive, which
        // could leave an empty panel visible and strand the screensaver photo
        // shrunk to the left with a blank strip on the right.
        if (getTotalCardCount() == 0) {
            hidePanel()
        }
    }

    // ── Persistent Layout ─────────────────────────────────────────

    /**
     * Show the panel in persistent layout mode (post-trigger).
     * Only video feed cards are on the panel; music/timer have been returned to their normal positions.
     */
    fun showPersistentPanel() {
        isPersistentLayoutActive = true
        showPanelIfNeeded()
        updateBgView()
        bgView?.visibility = View.VISIBLE
        bgView?.alpha = SIDEBAR_BG_ALPHA
        relayout()
        Log.i(TAG, "Persistent panel shown (layoutMode=$layoutMode)")
    }

    /**
     * Hide the persistent panel and reset state.
     */
    fun hidePersistentPanel() {
        if (!isPersistentLayoutActive) return
        isPersistentLayoutActive = false
        if (!isScreensaverActive && !isSidebarActive && getTotalCardCount() == 0) {
            hidePanel()
        }
        Log.i(TAG, "Persistent panel hidden")
    }

    // ── Panel Management ──────────────────────────────────────────

    /**
     * Called by participants after adding a card to the panel.
     * Shows the panel if needed and recalculates layout.
     */
    fun notifyCardAdded() {
        // Safety net: a participant may migrate a View that doesn't count as a
        // live card (e.g. a reconnecting/"Waiting…" music strip while MA is
        // unreachable). Never show an empty panel — that strands the screensaver
        // photo beside a blank strip. If nothing live remains, keep/​make the
        // panel hidden (unless sidebar/persistent layouts own it).
        val total = getTotalCardCount()
        if (total == 0) {
            PersistentLog.info(SS_LOG, "Card added ignored — no live cards (total=0)")
            if (!isSidebarActive && !isPersistentLayoutActive) hidePanel()
            return
        }
        showPanelIfNeeded()
        PersistentLog.info(SS_LOG, "Card added (total=$total)")
        notifyItemCounts()
        // Relayout after notifications so minimize/expand changes are reflected in positioning
        relayout()
    }

    /**
     * Called by participants after removing a card from the panel.
     * Hides the panel if empty and recalculates layout.
     * Item counts are notified BEFORE relayout so mode changes (e.g., restore-to-maximized)
     * take effect before positioning.
     */
    fun notifyCardRemoved() {
        val total = getTotalCardCount()
        PersistentLog.info(SS_LOG, "Card removed (total=$total)")
        if (total == 0) {
            hidePanel()
        }
        notifyItemCounts()
        if (total > 0) {
            relayout()
        }
    }

    /** Public relayout trigger for participants that change card dimensions after initial layout. */
    fun requestRelayout() {
        if (getTotalCardCount() > 0) relayout()
    }

    /**
     * True when we're on a narrow-portrait device (Fire tablet portrait ~601dp,
     * Samsung SM-X200 portrait) AND the screensaver is active. In this mode the
     * panel stacks vertically — top 50% photo, middle 25% horizontal camera strip,
     * bottom 25% music card — instead of the right-sidebar layout used on wider
     * devices. Collapses to 66/34 when only one card type is present.
     */
    fun isNarrowPortraitScreensaver(): Boolean {
        if (!isScreensaverActive) return false
        val widthDp = context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density
        return widthDp < 750f
    }

    /** The shared panel FrameLayout for participants to add cards to */
    fun getPanel(): FrameLayout = panel

    /**
     * Width of the feed area in pixels (dynamic based on layout mode).
     *
     * During the screensaver the split is fixed at the "medium" preset (~33%).
     * `feedSize` is a mutable field only ever written by VideoFeedOverlayManager
     * and is never reset, so a stale "large"/"xl" value (or a music/timer-driven
     * panel that never set it) could otherwise reserve 50–75% of the screen.
     * Card sizing (constrainCardToPanel) is minOf-bounded by this width, so
     * clamping here clamps the cards too — no overflow.
     */
    fun getFeedAreaWidth(): Int = when (layoutMode) {
        "grid" -> context.resources.displayMetrics.widthPixels
        else -> {
            val sizeForPanel = if (isScreensaverActive) "medium" else feedSize
            val cardWidth = VideoFeedStyles.cardWidthPx(context, sizeForPanel)
            val margin = dpToPx(MARGIN_DP)
            cardWidth + 2 * margin
        }
    }

    /** X-start of the feed area */
    fun getFeedAreaStart(): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return screenWidth - getFeedAreaWidth()
    }

    /** Get the scroll container for scrollable participants. Creates lazily. */
    fun getScrollContainer(): LinearLayout {
        if (scrollContent != null) return scrollContent!!
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val sv = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        scrollView = sv
        scrollContent = content
        panel.addView(sv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        return content
    }

    private fun showPanelIfNeeded() {
        if (panel.visibility == View.VISIBLE) return

        // Add black background on the right 1/3
        ensureBgView()
        // Allow cards to be drawn outside ScrollView/panel bounds (drag-to-unpin gesture)
        panel.clipChildren = false
        panel.clipToPadding = false
        panel.visibility = View.VISIBLE

        // In narrow portrait we don't shrink the photo horizontally — reset any
        // prior shrink state (false, 0) so the photo stays full-width. The
        // narrow-portrait relayout() branch uses bgView to cover the bottom band.
        if (isScreensaverActive) {
            if (isNarrowPortraitScreensaver()) {
                onResizeScreensaver?.invoke(false, 0)
            } else {
                onResizeScreensaver?.invoke(true, getFeedAreaWidth())
            }
        }
        Log.i(TAG, "Panel shown")
        PersistentLog.info(SS_LOG, "Panel shown (cards=${getTotalCardCount()}, " +
            "feedAreaWidth=${getFeedAreaWidth()}, screensaver=$isScreensaverActive, " +
            "sidebar=$isSidebarActive)")
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
        removeBgView()
        removeScrollView()
        isPersistentLayoutActive = false
        onResizeScreensaver?.invoke(false, 0)
        onScreensaverBottomInset?.invoke(0)
        // Photo is alone iff we're still in narrow-portrait screensaver with
        // nothing on the panel. Use fit-with-bars so landscape shots center
        // vertically instead of cropping edges.
        onScreensaverPhotoAlone?.invoke(isNarrowPortraitScreensaver())
        Log.i(TAG, "Panel hidden")
        PersistentLog.info(SS_LOG, "Panel hidden")
    }

    private fun ensureBgView() {
        if (bgView == null) {
            bgView = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                }
                isClickable = false
            }
        }
        val feedWidth = getFeedAreaWidth()
        val bgGravity = if (layoutMode == "grid") Gravity.NO_GRAVITY else Gravity.END
        val bgLp = FrameLayout.LayoutParams(feedWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = bgGravity
        }
        bgView?.let {
            if (it.parent == null) {
                panel.addView(it, 0, bgLp)
            } else {
                it.layoutParams = bgLp
            }
        }
    }

    /** Update bgView dimensions/gravity when layout mode changes */
    fun updateBgView() {
        val bg = bgView ?: return
        val feedWidth = getFeedAreaWidth()
        val lp = bg.layoutParams as? FrameLayout.LayoutParams ?: return
        if (layoutMode == "grid") {
            // Grid: full-screen overlay set in relayout()
            lp.gravity = Gravity.NO_GRAVITY
            lp.leftMargin = 0
        } else {
            // Sidebar/float: right-aligned panel-width bg
            lp.width = feedWidth
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END
            lp.leftMargin = 0
        }
        bg.layoutParams = lp
    }

    private fun removeBgView() {
        bgView?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun removeScrollView() {
        scrollContent?.removeAllViews()
        scrollView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        scrollView = null
        scrollContent = null
    }

    // ── Layout ────────────────────────────────────────────────────

    /**
     * Recalculate vertical positions of all cards on the panel.
     * Cards are stacked by participant priority (lower = higher on screen).
     *
     * When [SCROLL_THRESHOLD]+ scrollable cards exist, fixed cards (timer/music)
     * are placed at the top and a ScrollView fills the remaining space for video feeds.
     * Below threshold, scrollable cards are treated as fixed (positioned directly).
     */
    private fun relayout() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val realSize = android.graphics.Point()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealSize(realSize)
        val screenHeight = realSize.y
        val feedAreaWidth = getFeedAreaWidth()
        val feedAreaStart = getFeedAreaStart()
        val margin = dpToPx(MARGIN_DP)
        val gap = dpToPx(GAP_DP)

        // Count scrollable cards to decide mode
        data class CardEntry(val view: View, val width: Int, val height: Int)
        val fixedCards = mutableListOf<CardEntry>()
        var scrollableCount = 0
        val scrollableCards = mutableListOf<CardEntry>()

        for (p in participants) {
            if (p.isScrollable) {
                scrollableCount += p.getScreensaverCardCount()
                for (card in p.getScreensaverCards()) {
                    val lp = card.layoutParams as? FrameLayout.LayoutParams ?: continue
                    scrollableCards.add(CardEntry(card, lp.width, lp.height))
                }
                continue
            }
            for (card in p.getScreensaverCards()) {
                val lp = card.layoutParams as? FrameLayout.LayoutParams ?: continue
                fixedCards.add(CardEntry(card, lp.width, lp.height))
            }
        }

        // ── Narrow portrait screensaver: 50/25/25 vertical stack ──
        if (isNarrowPortraitScreensaver()) {
            layoutNarrowPortraitScreensaver(screenWidth, screenHeight, margin, gap)
            return
        }

        // ── Grid mode: multi-column layout for persistent panel ──
        Log.i(TAG, "relayout: layoutMode=$layoutMode, isPersistent=$isPersistentLayoutActive, " +
            "scrollable=${scrollableCards.size}, fixed=${fixedCards.size}, " +
            "screenSaver=$isScreensaverActive, sidebar=$isSidebarActive")
        if (layoutMode == "grid" && isPersistentLayoutActive && scrollableCards.isNotEmpty()) {
            scrollView?.visibility = View.GONE
            val allCards = fixedCards + scrollableCards
            val count = allCards.size
            val (rows, cols) = gridDimensions(count)
            // Calculate card size from VideoFeedStyles (not from LayoutParams which may be scaled)
            val fullCardW = VideoFeedStyles.cardWidthPx(context, feedSize)
            val fullCardH = VideoFeedStyles.cardHeightPx(context, feedSize)

            // Preview mode: when camera popout menu is open, shift grid right and scale down
            val menuClearance = if (isMenuOpen) dpToPx(MENU_CLEARANCE_DP) else 0
            val availableWidth = screenWidth - menuClearance
            val scale = if (isMenuOpen) {
                val maxGridW = cols * fullCardW + (cols - 1) * gap
                minOf(0.6f, availableWidth.toFloat() / maxOf(1, maxGridW + 2 * margin))
            } else 1f

            // Use view scaling for preview mode (scales labels proportionally)
            // LayoutParams stay at full card size; scaleX/Y shrinks the rendered view
            val useViewScale = isMenuOpen && scale < 1f
            val cardW = if (useViewScale) fullCardW else (fullCardW * scale).toInt()
            val cardH = if (useViewScale) fullCardH else (fullCardH * scale).toInt()
            val layoutCardW = (fullCardW * scale).toInt()
            val layoutCardH = (fullCardH * scale).toInt()

            Log.i(TAG, "Grid layout: count=$count, rows=$rows, cols=$cols, cardW=$layoutCardW, cardH=$layoutCardH, preview=$isMenuOpen, scale=$scale")
            val dismissHeight = dpToPx(DISMISS_CLEARANCE_DP)
            val totalW = cols * layoutCardW + (cols - 1) * gap
            val totalH = rows * layoutCardH + (rows - 1) * gap
            val startX = menuClearance + (availableWidth - totalW) / 2
            val startY = maxOf(margin, (screenHeight - totalH - dismissHeight) / 2)

            for ((i, entry) in allCards.withIndex()) {
                val col = i % cols
                val row = i / cols
                val lp = entry.view.layoutParams as? FrameLayout.LayoutParams ?: continue
                lp.width = cardW
                lp.height = cardH
                lp.gravity = Gravity.NO_GRAVITY
                lp.leftMargin = startX + col * (layoutCardW + gap)
                lp.topMargin = startY + row * (layoutCardH + gap)
                lp.rightMargin = 0
                lp.bottomMargin = 0
                entry.view.layoutParams = lp
                if (useViewScale) {
                    entry.view.pivotX = 0f
                    entry.view.pivotY = 0f
                    entry.view.scaleX = scale
                    entry.view.scaleY = scale
                } else {
                    entry.view.scaleX = 1f
                    entry.view.scaleY = 1f
                }
            }

            // Full-screen overlay behind grid (covers everything, kiosk sidebar is in WebView below)
            bgView?.let { bg ->
                bg.visibility = View.VISIBLE
                bg.alpha = SIDEBAR_BG_ALPHA
                val bgLp = bg.layoutParams as? FrameLayout.LayoutParams ?: return@let
                bgLp.width = FrameLayout.LayoutParams.MATCH_PARENT
                bgLp.height = FrameLayout.LayoutParams.MATCH_PARENT
                bgLp.gravity = Gravity.NO_GRAVITY
                bgLp.leftMargin = 0
                bg.layoutParams = bgLp
            }

            bringDismissToFront()
            return
        }

        val useScrollMode = scrollableCount >= SCROLL_THRESHOLD

        // If not in scroll mode, include scrollable cards as fixed (old behavior)
        if (!useScrollMode) {
            scrollView?.visibility = View.GONE
            fixedCards.addAll(scrollableCards)
        }

        if (fixedCards.isEmpty() && !useScrollMode) {
            bringDismissToFront()
            return
        }

        // Full-panel card (maximized music player) — fills entire panel
        val fullPanelCard = fixedCards.singleOrNull()?.takeIf {
            !useScrollMode &&
            (it.width == FrameLayout.LayoutParams.MATCH_PARENT ||
             it.height == FrameLayout.LayoutParams.MATCH_PARENT)
        }
        if (fullPanelCard != null) {
            val lp = fullPanelCard.view.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.width = feedAreaWidth
            lp.height = screenHeight
            lp.gravity = Gravity.NO_GRAVITY
            lp.leftMargin = feedAreaStart
            lp.topMargin = 0
            lp.rightMargin = 0
            lp.bottomMargin = 0
            fullPanelCard.view.layoutParams = lp
            scrollView?.visibility = View.GONE
            bringDismissToFront()
            return
        }

        // ── Compact PiP mode (1-2 triggered cards) ──
        // Bottom-right corner with compact background; full sidebar kicks in at 3+
        val isCompactPip = isSidebarActive && !isScreensaverActive && !isPersistentLayoutActive &&
            fixedCards.size in 1..2 && !useScrollMode
        if (isCompactPip) {
            val dismissHeight = dpToPx(DISMISS_CLEARANCE_DP)
            val totalCardsHeight = fixedCards.sumOf { it.height } + (fixedCards.size - 1) * gap
            // Uniform margin: top + cards + bottom margin + dismiss area
            val compactHeight = margin + totalCardsHeight + margin + dismissHeight

            // Cards start one margin below the top of the compact bg
            val bgTop = screenHeight - compactHeight
            var currentCardY = bgTop + margin

            for (entry in fixedCards) {
                val lp = entry.view.layoutParams as? FrameLayout.LayoutParams ?: continue
                val effectiveWidth = minOf(entry.width, feedAreaWidth - 2 * margin)
                lp.width = effectiveWidth
                lp.gravity = Gravity.NO_GRAVITY
                lp.leftMargin = feedAreaStart + (feedAreaWidth - effectiveWidth) / 2
                lp.topMargin = currentCardY
                lp.rightMargin = 0
                lp.bottomMargin = 0
                entry.view.layoutParams = lp
                currentCardY += entry.height + gap
            }

            bgView?.let { bg ->
                val bgLp = bg.layoutParams as? FrameLayout.LayoutParams ?: return@let
                bgLp.height = compactHeight
                bgLp.gravity = Gravity.BOTTOM or Gravity.END
                bgLp.width = feedAreaWidth
                bg.layoutParams = bgLp
                bg.alpha = SIDEBAR_BG_ALPHA
                // Rounded top corners for compact mode
                val r = dpToPx(COMPACT_CORNER_RADIUS_DP).toFloat()
                (bg.background as? GradientDrawable)?.cornerRadii =
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, 0f, 0f) // top-left, top-right only
            }
            bringDismissToFront()
            return
        }

        // ── Standard sidebar/screensaver card positioning ──
        // Restore full-height bgView for 3+ cards or screensaver
        if (isSidebarActive && !isScreensaverActive) {
            bgView?.let { bg ->
                val bgLp = bg.layoutParams as? FrameLayout.LayoutParams ?: return@let
                bgLp.height = FrameLayout.LayoutParams.MATCH_PARENT
                bgLp.gravity = Gravity.END
                bg.layoutParams = bgLp
                // Reset corners for full-height sidebar
                (bg.background as? GradientDrawable)?.cornerRadii = null
            }
        }

        // Position fixed cards (timer, music — at top)
        var currentY: Int
        if (useScrollMode) {
            // Fixed cards at top when scroll mode is active
            currentY = margin
        } else {
            // Center all cards vertically (sidebar mode)
            val totalHeight = fixedCards.sumOf { it.height } + (fixedCards.size - 1) * gap
            currentY = maxOf(margin, (screenHeight - totalHeight) / 2)
        }

        for (entry in fixedCards) {
            val lp = entry.view.layoutParams as? FrameLayout.LayoutParams ?: continue
            val effectiveWidth = minOf(entry.width, feedAreaWidth - 2 * margin)
            lp.width = effectiveWidth
            lp.gravity = Gravity.NO_GRAVITY
            lp.leftMargin = feedAreaStart + (feedAreaWidth - effectiveWidth) / 2
            lp.topMargin = currentY
            lp.rightMargin = 0
            lp.bottomMargin = 0
            entry.view.layoutParams = lp
            // Reset any view scaling from grid preview mode
            entry.view.scaleX = 1f
            entry.view.scaleY = 1f
            currentY += entry.height + gap
        }

        // Position ScrollView below fixed cards
        if (useScrollMode) {
            val sv = scrollView ?: return
            sv.visibility = View.VISIBLE
            val dismissClearance = dpToPx(DISMISS_CLEARANCE_DP)
            val scrollHeight = maxOf(0, screenHeight - currentY - dismissClearance)
            val svLp = sv.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(feedAreaWidth, scrollHeight)
            svLp.width = feedAreaWidth
            svLp.height = scrollHeight
            svLp.gravity = Gravity.NO_GRAVITY
            svLp.leftMargin = feedAreaStart
            svLp.topMargin = currentY
            svLp.rightMargin = 0
            svLp.bottomMargin = 0
            sv.layoutParams = svLp
        }

        bringDismissToFront()
    }

    /**
     * Narrow-portrait screensaver layout:
     *   top 50%  — photo slideshow (left full-screen, cards don't cover it)
     *   mid 25%  — horizontal camera strip (scrollable cards laid out left-to-right)
     *   bot 25%  — music card full-width
     * Collapses to 66/34 when only one card type is present, 100% photo when
     * nothing is playing (handled by the panel being hidden entirely upstream).
     * Hides the sidebar bgView — we don't want a dark band covering the photo.
     */
    private fun layoutNarrowPortraitScreensaver(
        screenWidth: Int, screenHeight: Int, margin: Int, gap: Int
    ) {
        // Collect cards by participant type (music vs scrollable = video feeds)
        val musicCards = mutableListOf<View>()
        val cameraCards = mutableListOf<View>()
        for (p in participants) {
            if (p.isScrollable) {
                cameraCards.addAll(p.getScreensaverCards())
            } else if (p.participantId == "music") {
                musicCards.addAll(p.getScreensaverCards())
            }
        }

        val hasMusic = musicCards.isNotEmpty()
        val hasCameras = cameraCards.isNotEmpty()

        // Decide zone heights:
        //   both present → 45% photo / 30% cameras / 25% music
        //     (camera zone of 30% yields a card with ~12.5% label ratio,
        //      matching Samsung landscape; 25% was too short and made the
        //      36dp label look disproportionately tall.)
        //   music only   → 66% photo / 34% music
        //   cameras only → 66% photo / 34% cameras
        val cameraZoneHeight = when {
            hasMusic && hasCameras -> (screenHeight * 30) / 100
            hasCameras -> (screenHeight * 34) / 100
            else -> 0
        }
        val musicZoneHeight = when {
            hasMusic && hasCameras -> screenHeight / 4
            hasMusic -> (screenHeight * 34) / 100
            else -> 0
        }
        val cameraZoneTop = screenHeight - cameraZoneHeight - musicZoneHeight
        val musicZoneTop = screenHeight - musicZoneHeight
        val coveredTop = when {
            hasMusic && hasCameras -> cameraZoneTop
            hasMusic -> musicZoneTop
            hasCameras -> cameraZoneTop
            else -> screenHeight
        }
        val coveredHeight = screenHeight - coveredTop

        // Tell the photo slideshow to shrink its foreground photos to fit the
        // top portion — just like the photo widget sizes to its slot. The blur
        // background still extends full-screen behind the card band.
        onScreensaverBottomInset?.invoke(coveredHeight)
        // Cards present → photo isn't alone → keep default CENTER_CROP.
        onScreensaverPhotoAlone?.invoke(false)

        // Repurpose the bgView: instead of a right-side sidebar band, use it as a
        // full-width opaque black band covering the bottom portion. This visually
        // crops the photo to the top area (matching the "photo widget" look the
        // user wants) without physically resizing the photoContainer.
        scrollView?.visibility = View.GONE
        bgView?.let { bg ->
            if (coveredHeight > 0) {
                bg.visibility = View.VISIBLE
                bg.alpha = 1.0f
                val lp = (bg.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, coveredHeight
                    )
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = coveredHeight
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.leftMargin = 0
                lp.topMargin = 0
                bg.layoutParams = lp
                (bg.background as? GradientDrawable)?.cornerRadii = null
            } else {
                bg.visibility = View.GONE
            }
        }

        // Music card: centered horizontally with a visible gutter on both sides
        // (matches the camera card's centered feel). Cap at 500dp so the strip
        // doesn't span edge-to-edge on portrait tablets.
        if (hasMusic) {
            val card = musicCards.first()
            val musicMaxW = dpToPx(500)
            val musicW = musicMaxW.coerceAtMost(screenWidth - 2 * margin)
            val musicLeft = ((screenWidth - musicW) / 2).coerceAtLeast(margin)
            val lp = card.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(musicW, musicZoneHeight)
            lp.width = musicW
            lp.height = musicZoneHeight
            lp.gravity = Gravity.NO_GRAVITY
            lp.leftMargin = musicLeft
            lp.topMargin = musicZoneTop
            lp.rightMargin = 0
            lp.bottomMargin = 0
            card.layoutParams = lp
            card.scaleX = 1f; card.scaleY = 1f
        }

        // Camera cards: horizontal strip in the middle zone.
        // Size to fill the zone height (minus label bar) at the natural 16:9
        // aspect ratio — in narrow portrait, percentage-based feedSize comes
        // out too small (Fire medium ≈ 200dp vs Samsung landscape ≈ 440dp).
        // Zone-height sizing keeps it visually proportional to the Samsung
        // landscape reference. Center the group horizontally, and vertically
        // within the zone.
        var cardW = 0
        var cardH = 0
        var firstCardLeft = 0
        if (cameraCards.isNotEmpty() && cameraZoneHeight > 0) {
            // Narrow portrait uses a half-height label bar so the bottom
            // strip doesn't dominate the card visually.
            for (card in cameraCards) {
                (card as? com.dashieapp.Dashie.halite.videofeed.VideoFeedCard)?.compactLabelBar = true
            }
            val labelPx = dpToPx(VideoFeedStyles.LABEL_HEIGHT_DP / 2)
            val videoH = (cameraZoneHeight - labelPx).coerceAtLeast(0)
            cardW = (videoH * VideoFeedStyles.ASPECT_RATIO).toInt()
            // Ensure the card doesn't exceed the screen width minus a reserve
            // on the left for the "Dismiss Cameras" pill (and the same on the
            // right for symmetry). With the 30% camera zone the zone-based
            // card typically fits comfortably (e.g. 445dp card in 601dp
            // screen leaves 78dp per side — plenty for the pill).
            val pillReservePx = dpToPx(100)
            val maxCardW = (screenWidth - 2 * pillReservePx)
                .coerceAtLeast(dpToPx(240))
            if (cardW > maxCardW) {
                cardW = maxCardW
                val adjustedVideoH = (cardW / VideoFeedStyles.ASPECT_RATIO).toInt()
                cardH = adjustedVideoH + labelPx
            } else {
                cardH = videoH + labelPx
            }
            val totalW = cameraCards.size * cardW +
                (cameraCards.size - 1).coerceAtLeast(0) * gap
            val startX = ((screenWidth - totalW) / 2).coerceAtLeast(margin)
            firstCardLeft = startX
            val cardTop = cameraZoneTop + ((cameraZoneHeight - cardH) / 2).coerceAtLeast(0)
            var xCursor = startX
            for (card in cameraCards) {
                val lp = card.layoutParams as? FrameLayout.LayoutParams ?: continue
                lp.width = cardW
                lp.height = cardH
                lp.gravity = Gravity.NO_GRAVITY
                lp.leftMargin = xCursor
                lp.topMargin = cardTop
                lp.rightMargin = 0
                lp.bottomMargin = 0
                card.layoutParams = lp
                card.scaleX = 1f; card.scaleY = 1f
                xCursor += cardW + gap
            }
        }

        // Reposition the "Dismiss Cameras" button to the LEFT of the camera
        // card, vertically centered with it. VideoFeedDimOverlay.showDismissButtonIn
        // always rewrites the button's layoutParams to the sidebar-bottom
        // default AFTER the coordinator's relayout — so we need to apply our
        // position both synchronously (catches the already-attached case) and
        // via post (catches the "added after relayout" case, running in the
        // next frame once showDismissButtonIn has completed).
        if (hasCameras) {
            val cz = cameraZoneTop
            val czH = cameraZoneHeight
            val fcLeft = firstCardLeft
            positionNarrowDismissButton(cz, czH, fcLeft)
            panel.post { positionNarrowDismissButton(cz, czH, fcLeft) }
        }

        bringDismissToFront()
    }

    /** Position the Dismiss Cameras pill to the LEFT of the camera card (narrow portrait). */
    private fun positionNarrowDismissButton(
        cameraZoneTop: Int, cameraZoneHeight: Int, firstCardLeft: Int
    ) {
        val margin = dpToPx(MARGIN_DP)
        val gap = dpToPx(GAP_DP)
        for (i in 0 until panel.childCount) {
            val child = panel.getChildAt(i)
            if (child is android.widget.TextView && child.text?.toString() == "Dismiss Cameras") {
                val btnH = dpToPx(VideoFeedStyles.SIDEBAR_DISMISS_HEIGHT_DP)
                val pillMaxWidth = (firstCardLeft - margin - gap).coerceAtLeast(dpToPx(72))
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, btnH
                ).apply {
                    gravity = Gravity.NO_GRAVITY
                    topMargin = cameraZoneTop +
                        ((cameraZoneHeight - btnH) / 2).coerceAtLeast(0)
                    leftMargin = margin
                    rightMargin = 0
                    bottomMargin = 0
                }
                child.layoutParams = lp
                child.setPadding(dpToPx(12), 0, dpToPx(12), 0)
                child.maxWidth = pillMaxWidth
                break
            }
        }
    }

    /** Ensure dismiss button stays on top of all other panel children */
    private fun bringDismissToFront() {
        for (i in 0 until panel.childCount) {
            val child = panel.getChildAt(i)
            if (child is android.widget.TextView && child.text?.toString() == "Dismiss Cameras") {
                child.bringToFront()
                break
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────

    private fun getTotalCardCount(): Int = participants.sumOf { it.getScreensaverCardCount() }

    /**
     * Enforce the core panel invariant: a panel with zero cards must not be
     * visible, and the screensaver photo must be full-width. Guards against a
     * participant leaking the panel (failing to call notifyCardRemoved), which
     * would otherwise strand the photo shrunk to the left with a blank strip on
     * the right. Only acts on the plain screensaver case — sidebar and
     * persistent layouts own their own teardown.
     *
     * @return true if a stale panel was found and torn down.
     */
    private fun reconcilePanelState(): Boolean {
        if (panel.visibility == View.VISIBLE &&
            !isSidebarActive && !isPersistentLayoutActive &&
            getTotalCardCount() == 0
        ) {
            Log.w(TAG, "reconcilePanelState: stale empty panel — self-healing")
            PersistentLog.warn(SS_LOG, "Stale empty panel detected — self-healing (hidePanel)")
            hidePanel()
            return true
        }
        return false
    }

    /** Human-readable snapshot of panel state for diagnostics reports. */
    fun describePanelState(): String = buildString {
        val screenW = context.resources.displayMetrics.widthPixels
        appendLine("Panel visible: ${panel.visibility == View.VISIBLE}")
        appendLine("Screensaver active: $isScreensaverActive")
        appendLine("Sidebar active: $isSidebarActive")
        appendLine("Persistent layout active: $isPersistentLayoutActive")
        appendLine("Layout mode: $layoutMode")
        appendLine("Feed size (field): $feedSize")
        appendLine("Feed area width: ${getFeedAreaWidth()} px of $screenW px screen")
        appendLine("Total cards: ${getTotalCardCount()}")
        for (p in participants) {
            appendLine("  - ${p.participantId}: ${p.getScreensaverCardCount()} card(s)")
        }
        if (getTotalCardCount() == 0 && panel.visibility == View.VISIBLE &&
            !isSidebarActive && !isPersistentLayoutActive) {
            appendLine("⚠️ STALE PANEL: visible with 0 cards — screensaver photo is stranded shrunk")
        }
    }

    private fun notifyItemCounts() {
        val total = getTotalCardCount()
        val breakdown = participants.joinToString(", ") { "${it.participantId}=${it.getScreensaverCardCount()}" }
        Log.d(TAG, "notifyItemCounts: total=$total ($breakdown)")
        for (p in participants) {
            p.onPanelItemCountChanged(total)
        }
    }

    /** Calculate grid dimensions (rows × cols) for a given card count */
    private fun gridDimensions(count: Int): Pair<Int, Int> = when {
        count <= 1 -> 1 to 1
        count == 2 -> 1 to 2
        count <= 4 -> 2 to 2
        count <= 6 -> 2 to 3
        else -> 3 to 3
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}

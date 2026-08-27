package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Renders the video feed strip — a bottom bar showing all subscribed feeds
 * as small thumbnails in a horizontal scroll.
 *
 * Vertical structure:
 *   [Top gray bar: X | video icon + "Video Feeds" | maximize (placeholder)]
 *   [Left controls (Feeds/Alerts toggles) | Horizontal scroll of feed cards]
 */
class VideoFeedStripRenderer(
    private val context: Context,
    private val isPaused: Boolean,
    private val isAlertsMuted: Boolean,
    private val offlineCount: Int,
    private val onClose: () -> Unit,
    private val onMaximize: () -> Unit,
    private val onTogglePause: (paused: Boolean) -> Unit,
    private val onToggleAlertsMuted: (muted: Boolean) -> Unit,
    private val onToggleShowOffline: (show: Boolean) -> Unit,
    private val onFeedTapped: (ruleId: String) -> Unit,
    private val onFeedDragUp: (ruleId: String, card: VideoFeedCard) -> Unit
) {

    companion object {
        private const val TAG = "VideoFeedStripRenderer"
    }

    private val isDark get() = VideoFeedStyles.isDarkMode(context)
    private fun dp(dp: Int) = VideoFeedStyles.dpToPx(context, dp)

    private var scrollContainer: LinearLayout? = null
    private var scrollView: HorizontalScrollView? = null
    private var feedsPausedState = isPaused
    private var alertsMutedState = isAlertsMuted
    private var showOfflineState = false  // Default: hide offline feeds
    // Label TextView inside the "Offline (N)" toggle. Kept so the N count can
    // be updated dynamically as feeds go online/offline during the session —
    // previously the count was static from renderer construction time.
    private var offlineLabelView: TextView? = null

    // Track feed cards for cleanup
    private val stripCards = LinkedHashMap<String, VideoFeedCard>()
    /** Track which feeds are offline (ruleId → isAvailable) */
    private val feedAvailability = LinkedHashMap<String, Boolean>()

    /** Callback when the strip should resize (e.g., offline feeds toggled). */
    var onRequestResize: (() -> Unit)? = null

    /** Called when a strip card starts being dragged up.
     *  Params: ruleId, card, card's screen location (x, y).
     *  Caller should reparent the card above the strip for visibility. */
    var onFeedDragUpStarted: ((ruleId: String, card: VideoFeedCard) -> Unit)? = null

    /** Total height of the strip in dp (top bar + content with reduced padding). */
    val heightDp: Int
        get() {
            val cardHeightPx = VideoFeedStyles.cardHeightPx(context, "strip")
            val cardHeightDp = (cardHeightPx / context.resources.displayMetrics.density).toInt()
            // Use smaller padding (6dp each side) for a tighter strip
            return VideoFeedStyles.STRIP_TOP_BAR_HEIGHT_DP + cardHeightDp + 12
        }

    /**
     * Build the strip UI and add it to the parent.
     */
    fun render(parent: ViewGroup) {
        val cornerR = dp(VideoFeedStyles.CORNER_RADIUS_DP * 2).toFloat()
        val outerWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                setColor(if (isDark) VideoFeedStyles.BG_COLOR_DARK else VideoFeedStyles.BG_COLOR_LIGHT)
                cornerRadii = floatArrayOf(cornerR, cornerR, cornerR, cornerR, 0f, 0f, 0f, 0f)
            }
            elevation = dp(12).toFloat()
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + cornerR.toInt(), cornerR)
                }
            }
            if (isDark && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = 0x40FFFFFF.toInt()
                outlineSpotShadowColor = 0x50FFFFFF.toInt()
            }
        }

        buildTopBar(outerWrapper)
        buildContentRow(outerWrapper)

        parent.addView(outerWrapper)
    }

    private fun buildTopBar(outerWrapper: LinearLayout) {
        val barHeight = dp(VideoFeedStyles.STRIP_TOP_BAR_HEIGHT_DP)

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barHeight
            )
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(if (isDark) 0xFF2A2A2A.toInt() else 0x80000000.toInt())
        }

        // Close (X) button — left side
        val closeButton = ImageButton(context).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true; isFocusable = true
            setOnClickListener { onClose() }
        }
        topBar.addView(closeButton)

        // Title section (centered, fills remaining space)
        val titleSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Video icon
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
            setImageResource(R.drawable.ic_videocam)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            titleSection.addView(this)
        }
        // "Video Feeds" text
        TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, VideoFeedStyles.scaledSp(context, 15f))
            maxLines = 1
            text = "Video Feeds"
            titleSection.addView(this)
        }
        topBar.addView(titleSection)

        // Maximize button — right side (placeholder, no-op for now)
        val maximizeButton = ImageButton(context).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setColorFilter(Color.WHITE)
            setImageResource(R.drawable.ic_expand)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true; isFocusable = true
            alpha = 0.4f  // Dimmed since it's a placeholder
            setOnClickListener { /* no-op for now */ }
        }
        topBar.addView(maximizeButton)

        outerWrapper.addView(topBar)
    }

    private fun buildContentRow(outerWrapper: LinearLayout) {
        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            val contentBg = GradientDrawable().apply {
                setColor(if (isDark) VideoFeedStyles.BG_COLOR_DARK else VideoFeedStyles.BG_COLOR_LIGHT)
                setStroke(dp(1), if (isDark) VideoFeedStyles.BORDER_COLOR_DARK
                    else VideoFeedStyles.BORDER_COLOR_LIGHT)
            }
            background = contentBg
        }

        // Left controls panel (non-scrolling)
        buildControlsPanel(contentRow)

        // Separator line
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            }
            setBackgroundColor(if (isDark) VideoFeedStyles.BORDER_COLOR_DARK else VideoFeedStyles.BORDER_COLOR_LIGHT)
            contentRow.addView(this)
        }

        // Scroll area with chevron indicator
        val scrollFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            clipChildren = false
        }

        // Horizontal scroll of feed cards
        val sv = HorizontalScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }

        scrollContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(8), dp(6), dp(8), dp(6))
            clipChildren = false
        }

        sv.addView(scrollContainer)
        scrollFrame.addView(sv)
        scrollView = sv

        // Scroll chevron indicator (right edge, semi-transparent gray circle)
        val chevronSize = dp(28)
        val chevronBtn = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(chevronSize, chevronSize).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                rightMargin = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x60808080.toInt())
            }
            alpha = 0f  // Hidden initially, shown when scrollable
            isClickable = true
            setOnClickListener {
                // Scroll right by one card width
                val cardW = VideoFeedStyles.cardWidthPx(context, "strip") + dp(VideoFeedStyles.STRIP_CARD_GAP_DP)
                sv.smoothScrollBy(cardW, 0)
            }
        }
        ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(5), dp(5), dp(5), dp(5))
            chevronBtn.addView(this)
        }
        scrollFrame.addView(chevronBtn)

        // Update chevron visibility on scroll
        fun updateChevron() {
            val maxScroll = (scrollContainer?.width ?: 0) - sv.width
            val atEnd = sv.scrollX >= maxScroll - dp(8)
            val hasOverflow = (scrollContainer?.width ?: 0) > sv.width
            chevronBtn.animate().alpha(if (hasOverflow && !atEnd) 0.8f else 0f).setDuration(150).start()
        }
        sv.viewTreeObserver.addOnGlobalLayoutListener { updateChevron() }
        sv.setOnScrollChangeListener { _, _, _, _, _ -> updateChevron() }

        contentRow.addView(scrollFrame)
        outerWrapper.addView(contentRow)
    }

    private fun buildControlsPanel(parent: LinearLayout) {
        val controlsWidth = dp(150)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(controlsWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }

        // Pop-ups on/off toggle
        val feedsToggle = buildToggleRow("Pop-ups", R.drawable.ic_video_play, !feedsPausedState) { active ->
            feedsPausedState = !active
            onTogglePause(feedsPausedState)
        }
        panel.addView(feedsToggle)

        // Alerts toggle was moved to the global volume popout (sidebar) so the
        // mute control sits alongside speaker/mic mute. Kept as 2 toggles in
        // this panel now: Pop-ups and Offline.

        // Show/Hide Offline toggle with count. Label is kept live — the count
        // updates as feeds go online/offline during the session.
        val initialOfflineCount = offlineCount
        val offlineToggle = buildToggleRow(
            formatOfflineLabel(initialOfflineCount), R.drawable.ic_eye, showOfflineState,
        ) { active ->
            showOfflineState = active
            setShowOffline(active)
            onToggleShowOffline(active)
        }
        // Pull out the TextView for live updates — the toggle row puts the
        // label as its last child (see buildToggleRow).
        offlineLabelView = (offlineToggle.getChildAt(offlineToggle.childCount - 1) as? TextView)
        panel.addView(offlineToggle)

        parent.addView(panel)
    }

    /**
     * Build a horizontal toggle row: [icon] [label text]
     * Icon is accent/colored when active, gray with strikethrough when inactive.
     */
    private fun buildToggleRow(label: String, iconRes: Int, initialActive: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        var isActive = initialActive
        val accentColor = VideoFeedStyles.ACCENT_COLOR
        val inactiveColor = if (isDark) 0xFF666666.toInt() else 0xFFAAAAAA.toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) }
            setPadding(0, dp(2), 0, dp(2))
        }

        // Icon with strikethrough overlay when inactive
        val iconSize = dp(34)
        val iconFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(8) }
        }
        val iconView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageResource(iconRes)
            setColorFilter(if (isActive) accentColor else inactiveColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        iconFrame.addView(iconView)

        // Diagonal strikethrough line (hidden when active)
        val strikeLine = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(2), iconSize).apply {
                gravity = Gravity.CENTER
            }
            setBackgroundColor(inactiveColor)
            rotation = -45f
            visibility = if (isActive) View.GONE else View.VISIBLE
        }
        iconFrame.addView(strikeLine)
        row.addView(iconFrame)

        // Label text
        val labelView = TextView(context).apply {
            setTextColor(if (isDark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, VideoFeedStyles.scaledSp(context, 15f))
            text = label
            maxLines = 1
        }
        row.addView(labelView)

        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener {
            isActive = !isActive
            iconView.setColorFilter(if (isActive) accentColor else inactiveColor)
            strikeLine.visibility = if (isActive) View.GONE else View.VISIBLE
            onToggle(isActive)
        }

        return row
    }

    /**
     * Add a feed card to the horizontal scroll.
     * The card uses small size, no label bar overlay buttons, tap to select, drag-up to float.
     */
    fun addFeedCard(card: VideoFeedCard, ruleId: String, isAvailable: Boolean = true) {
        val container = scrollContainer ?: return
        val gap = dp(VideoFeedStyles.STRIP_CARD_GAP_DP)

        // Wrap in a FrameLayout for proper sizing
        val cardWidth = VideoFeedStyles.cardWidthPx(context, "strip")
        val cardHeight = VideoFeedStyles.cardHeightPx(context, "strip")
        val wrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                marginEnd = gap
            }
            clipChildren = false
        }

        // Configure card for strip mode: no buttons, tap = select, drag up = float
        // showOverlayControls=false is passed via constructor (initialShowOverlayControls)
        card.isDraggable = false
        card.isResizable = false
        card.tapToMaximize = false
        card.canDragToUnpin = false
        card.canDragUpToFloat = true
        card.onDragUpToFloat = { onFeedDragUp(ruleId, card) }
        card.onDragUpStarted = { onFeedDragUpStarted?.invoke(ruleId, card) }
        card.onStripTap = { onFeedTapped(ruleId) }

        wrapper.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Hide offline feeds if show-offline is off
        if (!isAvailable && !showOfflineState) {
            wrapper.visibility = View.GONE
        }

        container.addView(wrapper)
        stripCards[ruleId] = card
        feedAvailability[ruleId] = isAvailable
        Log.i(TAG, "Added feed card to strip: $ruleId available=$isAvailable (total: ${stripCards.size})")
    }

    /**
     * Remove a feed card from the strip.
     */
    fun removeFeedCard(ruleId: String) {
        val card = stripCards.remove(ruleId) ?: return
        val wrapper = card.parent as? ViewGroup
        val container = scrollContainer
        if (wrapper != null && container != null) {
            container.removeView(wrapper)
        }
        Log.i(TAG, "Removed feed card from strip: $ruleId (remaining: ${stripCards.size})")
    }

    /**
     * Release all strip cards (stop streams).
     */
    fun releaseAll() {
        stripCards.values.forEach { card ->
            val wrapper = card.parent as? ViewGroup
            wrapper?.removeView(card)
            card.release()
        }
        stripCards.clear()
    }

    /**
     * Highlight the active feed (the one shown in the drawer).
     */
    fun setActiveFeed(ruleId: String?) {
        // No dimming — all strip cards stay at full opacity regardless of selection
    }

    // ── D-pad navigation support ─────────────────────────────────────

    private var dpadHighlightedRuleId: String? = null

    /** Visible (selectable) feed rule ids in display order — the navigable set
     *  for d-pad feed selection. Offline feeds are excluded unless shown. */
    fun getSelectableRuleIds(): List<String> =
        stripCards.keys.filter { ruleId ->
            val available = feedAvailability[ruleId] ?: true
            available || showOfflineState
        }

    /**
     * Apply a d-pad focus ring to the given strip card (or clear when null),
     * and scroll it into view. The ring is drawn by the card itself on its
     * scaled content container so it matches the card's rounded corners.
     */
    fun highlightStripCard(ruleId: String?) {
        // Clear previous ring
        dpadHighlightedRuleId?.let { prev -> stripCards[prev]?.setDpadHighlight(false) }
        dpadHighlightedRuleId = ruleId
        if (ruleId == null) return
        val card = stripCards[ruleId] ?: return
        card.setDpadHighlight(true)
        // Scroll the highlighted card into view
        val wrapper = card.parent as? View ?: return
        scrollView?.post {
            scrollView?.smoothScrollTo((wrapper.left - dp(8)).coerceAtLeast(0), 0)
        }
    }

    /**
     * Show or hide offline feed cards in the strip, then resize.
     */
    private fun setShowOffline(show: Boolean) {
        stripCards.forEach { (ruleId, card) ->
            val isAvailable = feedAvailability[ruleId] ?: true
            if (!isAvailable) {
                val wrapper = card.parent as? View
                wrapper?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        scrollContainer?.requestLayout()
        scrollView?.requestLayout()
        onRequestResize?.invoke()
    }

    /** Find a strip card by its camera entity ID. */
    fun findCardByEntityId(entityId: String): VideoFeedCard? {
        return stripCards.values.firstOrNull { it.data.cameraEntityId == entityId }
    }

    /** Update availability for a feed and hide/show accordingly. */
    fun updateFeedAvailability(ruleId: String, available: Boolean) {
        val prev = feedAvailability[ruleId]
        if (prev == available) return  // no change, avoid unnecessary relayout
        feedAvailability[ruleId] = available
        val card = stripCards[ruleId] ?: return
        val wrapper = card.parent as? View ?: return
        // Handle both directions: a feed that comes back online after being
        // hidden for offline should be re-shown; a feed that goes offline
        // while "hide offline" is on should be hidden. Previously only the
        // offline direction updated visibility — once marked offline a feed
        // stayed hidden forever even after recovering.
        val newVisibility = if (!available && !showOfflineState) View.GONE else View.VISIBLE
        wrapper.visibility = newVisibility
        Log.i(TAG, "📊 Availability: ruleId=$ruleId available=$available prev=$prev showOffline=$showOfflineState → visibility=${if (newVisibility == View.GONE) "GONE" else "VISIBLE"}")
        refreshOfflineLabel()
        onRequestResize?.invoke()
    }

    /**
     * Update the "Offline (N)" label text based on the current
     * [feedAvailability] map. Called on every [updateFeedAvailability]
     * so the count stays current as feeds go on/offline.
     */
    private fun refreshOfflineLabel() {
        val count = feedAvailability.count { !it.value }
        offlineLabelView?.text = formatOfflineLabel(count)
    }

    private fun formatOfflineLabel(count: Int): String =
        if (count > 0) "Offline ($count)" else "Offline"

    /** Calculate the ideal width for the strip based on visible feeds + controls panel. */
    fun calculateIdealWidthPx(): Int {
        val controlsW = dp(150) + dp(1)  // Controls panel + separator
        val gap = dp(VideoFeedStyles.STRIP_CARD_GAP_DP)
        val cardW = VideoFeedStyles.cardWidthPx(context, "strip")
        val visibleCount = stripCards.count { (ruleId, _) ->
            val available = feedAvailability[ruleId] ?: true
            available || showOfflineState
        }
        val cardsW = visibleCount * (cardW + gap) + dp(16)  // + padding
        return controlsW + cardsW
    }
}

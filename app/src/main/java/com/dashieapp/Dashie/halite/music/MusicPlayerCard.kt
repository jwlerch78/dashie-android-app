package com.dashieapp.Dashie.halite.music

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Music player card view that displays now-playing info in normal, minimized, maximized, or strip mode.
 * Rendered natively in Kotlin so it appears over screensavers.
 * Supports drag-to-reposition.
 *
 * Delegates rendering to [MusicPlayerNormalRenderer], [MusicPlayerMaximizedRenderer],
 * and [MusicPlayerStripRenderer].
 * Touch/drag handling is in [MusicPlayerTouchHandler].
 * Shared styling in [MusicPlayerStyles].
 */
class MusicPlayerCard(
    context: android.content.Context,
    private val overlayContainer: ViewGroup,
    onPlayPause: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onNormal: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onStop: () -> Unit,
    onStrip: () -> Unit = {},
    onFloat: () -> Unit = {},
    onVolumeChange: ((Float) -> Unit)? = null,
    private val onPositionChanged: ((Int, Int) -> Unit)? = null,
    onSpeakerClicked: (() -> Unit)? = null,
    onPlayRecentItem: ((String) -> Unit)? = null,
    onHide: () -> Unit = {},
    onSpeakerDrawerToggle: (() -> Unit)? = null,
    onSpeakerJoin: ((String, Boolean) -> Unit)? = null,
    onSpeakerVolumeChange: ((String, Int) -> Unit)? = null,
    onSpeakerMuteToggle: ((String, Boolean) -> Unit)? = null,
    onGroupVolumeChange: ((Int) -> Unit)? = null,
    onGroupMuteToggle: ((Boolean) -> Unit)? = null,
    onTransferQueue: ((String, String) -> Unit)? = null,
    onClearQueue: ((String) -> Unit)? = null,
    onShuffleToggle: (() -> Unit)? = null,
    onRepeatCycle: (() -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null
) : FrameLayout(context) {

    companion object {
        private const val TAG = "MusicPlayerCard"
        private const val USER_ACTION_DEBOUNCE_MS = 1500L
    }

    val cardLayoutParams: FrameLayout.LayoutParams

    private var playerData: MusicPlayerData = MusicPlayerData()
    private var lastAlbumArtUrl: String? = null
    private var lastUserActionTime: Long = 0
    private var lastScrollTrackText: String = ""  // Track text that scroll was set up for
    private var lastScrollArtistText: String = ""  // Artist text that scroll was set up for
    private var firstRecentItem: RecentlyPlayedItem? = null  // For idle state display
    private var cachedRecentlyPlayed: RecentlyPlayedData? = null
    private var cachedMediaBrowserDataSource: MediaBrowserDataSource? = null
    private var cachedVolumeCoordinator: MusicStateCoordinator? = null
    private var savedSplitView = false  // Preserved across screensaver cycle
    private var savedVolumeExpanded = false  // Preserved across screensaver/mode switches
    private var hasCustomSize = false   // True after user proportional resize
    private var scaleContainer: FrameLayout? = null  // Holds normal-mode content at default size, scaled up
    private val defaultWidthPx = dp(MusicPlayerStyles.NORMAL_WIDTH_DP)

    private val callbacks = MusicPlayerCallbacks(
        onPlayPause, onMinimize, onMaximize, onNormal, onNext, onPrevious,
        // Reset split-view state on stop/hide so the next time the player is
        // opened it starts in the full-screen maximized view, not in a blank
        // media/speaker-selector split panel.
        onStop = { savedSplitView = false; onStop() },
        onStrip = onStrip, onFloat = onFloat,
        onVolumeChange = onVolumeChange, onSpeakerClicked = onSpeakerClicked,
        onPlayRecentItem = onPlayRecentItem,
        onHide = { savedSplitView = false; onHide() },
        onSpeakerDrawerToggle = onSpeakerDrawerToggle,
        onSpeakerJoin = onSpeakerJoin,
        onSpeakerVolumeChange = onSpeakerVolumeChange,
        onSpeakerMuteToggle = onSpeakerMuteToggle,
        onGroupVolumeChange = onGroupVolumeChange,
        onGroupMuteToggle = onGroupMuteToggle,
        onTransferQueue = onTransferQueue,
        onClearQueue = onClearQueue,
        onShuffleToggle = onShuffleToggle,
        onRepeatCycle = onRepeatCycle,
        onSeek = onSeek
    )

    private val onUserAction: () -> Unit = {
        lastUserActionTime = System.currentTimeMillis()
        playerData = playerData.copy(isPlaying = !playerData.isPlaying)
    }

    private var normalRenderer: MusicPlayerNormalRenderer? = null
    private var maximizedRenderer: MusicPlayerMaximizedRenderer? = null
    private var stripRenderer: MusicPlayerStripRenderer? = null
    /** Music profile manager for per-person music scoping (set externally) */
    var musicProfileManager: MusicProfileManager? = null
    private var lastStripWidthPx: Int = 0  // Captured after strip lays out; used by mini player to match width

    private val touchHandler = MusicPlayerTouchHandler(
        card = this,
        overlayContainer = overlayContainer,
        onPositionChanged = onPositionChanged,
        onMinimizedTap = { onStrip() }
    )

    // Minimized view references (inline — only 65 lines)
    private var albumArtMinimized: ImageView? = null
    private var playPauseButtonMinimized: ImageButton? = null
    private var playPauseSpinnerMinimized: android.view.View? = null
    private var trackArtistTextMinimized: TextView? = null
    private var trackScrollViewMinimized: HorizontalScrollView? = null
    private var scrollAnimatorMinimized: ValueAnimator? = null
    private var elapsedTextMinimized: TextView? = null
    // Tracks pending OnGlobalLayoutListeners per scroll view to prevent orphaned animators
    // when text changes rapidly (e.g. scrolling through recently played items)
    private val pendingScrollListeners = mutableMapOf<View, android.view.ViewTreeObserver.OnGlobalLayoutListener>()

    /** Fired when the strip height changes dynamically (e.g. recently played toggle). */
    var onStripHeightChangedDp: ((Int) -> Unit)? = null

    private fun dp(dp: Int) = MusicPlayerStyles.dpToPx(context, dp)

    init {
        cardLayoutParams = FrameLayout.LayoutParams(
            dp(MusicPlayerStyles.NORMAL_WIDTH_DP),
            dp(MusicPlayerStyles.NORMAL_HEIGHT_DP)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(MusicPlayerStyles.MARGIN_DP)
            bottomMargin = dp(MusicPlayerStyles.MARGIN_DP)
        }

        render()

        post {
            // Skip position restoration for maximized/strip — they use fixed layout params
            if (playerData.isMaximized || playerData.isStrip) return@post
            if (touchHandler.hasSavedPosition(isMinimized = false)) {
                touchHandler.restoreSavedPosition(isMinimized = false, cardLayoutParams)
            } else if (!touchHandler.hasCustomPosition) {
                val safeBottomMargin = dp(MusicPlayerStyles.MARGIN_DP + 60)
                touchHandler.currentX = overlayContainer.width - width - dp(MusicPlayerStyles.MARGIN_DP)
                touchHandler.currentY = overlayContainer.height - height - safeBottomMargin
            }
        }
    }

    fun setMinimized() {
        val prevHeight = height
        val wasStrip = playerData.isStrip
        // Close speaker drawer if open (stops polling via callback)
        if (stripRenderer?.speakerDrawerVisible == true) stripRenderer?.toggleSpeakerDrawer()
        if (normalRenderer?.speakerDrawerVisible == true) normalRenderer?.toggleSpeakerDrawer()
        touchHandler.savePositionForMode(isMinimized = playerData.isMinimized)
        hasCustomSize = false
        playerData = playerData.copy(isMinimized = true, isMaximized = false, isStrip = false)
        touchHandler.resetPosition()
        render()
        // Strip → Mini: both bottom-anchored, so offset by height difference so top edge
        // starts at the strip's top position, then slides down to mini's natural position
        if (wasStrip && prevHeight > 0) {
            val miniHeight = dp(MusicPlayerStyles.MINIMIZED_HEIGHT_DP)
            val offset = -(prevHeight - miniHeight).toFloat()
            translationY = offset
            animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    fun setMaximized() {
        touchHandler.savePositionForMode(isMinimized = playerData.isMinimized)
        hasCustomSize = false
        playerData = playerData.copy(isMinimized = false, isMaximized = true, isStrip = false)
        touchHandler.resetPosition()
        render()
    }

    fun setNormal() {
        touchHandler.savePositionForMode(isMinimized = playerData.isMinimized)
        hasCustomSize = false
        playerData = playerData.copy(isMinimized = false, isMaximized = false, isStrip = false)
        touchHandler.resetPosition()
        restoreAfterRender(isMinimized = false)
    }

    fun setStrip() {
        val wasMinimized = playerData.isMinimized
        val prevHeight = if (wasMinimized) dp(MusicPlayerStyles.MINIMIZED_HEIGHT_DP) else 0
        touchHandler.savePositionForMode(isMinimized = playerData.isMinimized)
        hasCustomSize = false
        playerData = playerData.copy(isMinimized = false, isMaximized = false, isStrip = true)
        touchHandler.resetPosition()
        if (wasMinimized && prevHeight > 0) {
            // Hide before render to prevent flash
            visibility = View.INVISIBLE
        }
        render()
        if (wasMinimized && prevHeight > 0) {
            // Mini → Strip: offset so top starts at mini's top, then slides up to strip position
            post {
                val stripHeight = height
                val offset = (stripHeight - prevHeight).toFloat()
                translationY = offset
                visibility = View.VISIBLE
                animate()
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
        // Note: first-time entrance animation is handled by OverlayManager.animateEntrance()
    }

    /**
     * Set the mode without rendering. Used when restoring mode before a forceRestyle()
     * call that will handle the render (e.g., screensaver deactivation).
     */
    fun setModeSilently(isMinimized: Boolean, isMaximized: Boolean, isStrip: Boolean = false) {
        hasCustomSize = false
        playerData = playerData.copy(isMinimized = isMinimized, isMaximized = isMaximized, isStrip = isStrip)
        touchHandler.resetPosition()
    }

    /**
     * Render, then restore saved position without flash.
     * Hides the card during the layout pass, restores position, then shows.
     */
    private fun restoreAfterRender(isMinimized: Boolean) {
        if (touchHandler.hasSavedPosition(isMinimized)) {
            visibility = View.INVISIBLE
            render()
            post {
                touchHandler.restoreSavedPosition(isMinimized, cardLayoutParams)
                visibility = View.VISIBLE
            }
        } else {
            render()
        }
    }

    fun updateState(data: MusicPlayerData) {
        val timeSinceUserAction = System.currentTimeMillis() - lastUserActionTime
        val inDebounceWindow = timeSinceUserAction < USER_ACTION_DEBOUNCE_MS

        var updatedData = data.copy(
            isMinimized = playerData.isMinimized,
            isMaximized = playerData.isMaximized,
            isStrip = playerData.isStrip
        )
        if (inDebounceWindow && playerData.isPlaying != data.isPlaying) {
            updatedData = updatedData.copy(isPlaying = playerData.isPlaying)
        }

        // Auto-expand volume section when transitioning from reconnecting to idle-connected
        // so recently played and speaker selector are immediately visible
        val wasReconnecting = playerData.isReconnecting
        val nowIdleConnected = !updatedData.hasMedia && !updatedData.isReconnecting && updatedData.entityId.isNotEmpty()
        val shouldAutoExpand = wasReconnecting && nowIdleConnected

        val modeChanged = playerData.isMinimized != updatedData.isMinimized ||
                         playerData.isMaximized != updatedData.isMaximized ||
                         playerData.isStrip != updatedData.isStrip
        playerData = updatedData

        if (modeChanged) render() else updateViews()

        if (shouldAutoExpand) {
            normalRenderer?.expandVolumeSection()
        }
    }

    private fun render() {
        removeAllViews()
        scaleContainer = null
        touchHandler.contentScale = 1f
        lastAlbumArtUrl = null  // Force album art reload on mode switch
        lastScrollTrackText = ""  // Force scroll re-setup on mode switch
        lastScrollArtistText = ""
        normalRenderer = null
        maximizedRenderer = null
        stripRenderer = null

        when {
            playerData.isStrip -> {
                val renderer = MusicPlayerStripRenderer(context, callbacks, onUserAction)
                renderer.idleSuggestion = firstRecentItem
                renderer.musicProfileManager = musicProfileManager
                renderer.onHeightChanged = { newHeightDp ->
                    cardLayoutParams.height = dp(newHeightDp)
                    layoutParams = cardLayoutParams
                    onStripHeightChangedDp?.invoke(newHeightDp)
                }
                renderer.render(this)
                stripRenderer = renderer
                cachedMediaBrowserDataSource?.let { renderer.setMediaBrowserDataSource(it) }
                cachedRecentlyPlayed?.let { renderer.updateRecentlyPlayed(it) }
                // Re-apply volume coordinator so volume displays immediately (not "-")
                cachedVolumeCoordinator?.let { coord ->
                    renderer.volumeCoordinator = coord
                    renderer.refreshVolumeDisplay()
                    renderer.refreshVolumeToggle()
                }

                // STRIP mode renders on the normal dashboard, where the Dashie
                // native sidebar sits on the left. The screensaver uses NORMAL mode
                // (compact card), not STRIP, so its centering is handled separately
                // by ScreensaverPanelCoordinator.
                //
                // Small screens (e.g. Echo Show 5, 788dp): the strip is a full-width
                // bottom bar (its root is MATCH_PARENT), so it must reserve the
                // sidebar width on the left and stretch to the right edge — the same
                // "render to the right of the sidebar" alignment the VideoFeedStrip
                // uses. Otherwise centering covers the sidebar. Wider screens
                // (Samsung SM-X200 landscape ~1280dp, Fire TV ~960dp) don't need the
                // full width, so keep the strip compact and centered rather than
                // stretched edge-to-edge with the controls flung to the corners.
                val widthDp = (context.resources.displayMetrics.widthPixels /
                    context.resources.displayMetrics.density).toInt()
                if (widthDp < MusicPlayerStyles.STRIP_COMPACT_MIN_WIDTH_DP) {
                    cardLayoutParams.width = LayoutParams.MATCH_PARENT
                    cardLayoutParams.setMargins(dp(MusicPlayerStyles.STRIP_SIDE_PADDING_DP), 0, 0, 0)
                    cardLayoutParams.gravity = Gravity.BOTTOM
                } else {
                    cardLayoutParams.width = LayoutParams.WRAP_CONTENT
                    cardLayoutParams.setMargins(0, 0, 0, 0)
                    cardLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                cardLayoutParams.height = dp(renderer.heightDp)
                post { lastStripWidthPx = width }
            }
            playerData.isMaximized -> {
                val renderer = MusicPlayerMaximizedRenderer(
                    context, overlayContainer, callbacks, onUserAction)
                renderer.idleSuggestion = firstRecentItem
                renderer.isSplitView = savedSplitView
                renderer.musicProfileManager = musicProfileManager
                renderer.onProfileSwitchRequested = { showProfileSwitcher() }
                renderer.onExplorerToggled = {
                    savedSplitView = maximizedRenderer?.isSplitView ?: false
                    lastAlbumArtUrl = null  // Force art reload into new ImageView
                    updateViews()
                }
                renderer.volumeCoordinator = cachedVolumeCoordinator
                renderer.render(this)
                maximizedRenderer = renderer
                cachedMediaBrowserDataSource?.let { renderer.setMediaBrowserDataSource(it) }
                cachedRecentlyPlayed?.let { renderer.updateRecentlyPlayed(listOf(it)) }
                musicProfileManager?.getActiveProfile()?.let { renderer.setMusicProfile(it) }

                cardLayoutParams.width = LayoutParams.MATCH_PARENT
                cardLayoutParams.height = LayoutParams.MATCH_PARENT
                cardLayoutParams.gravity = Gravity.NO_GRAVITY
                cardLayoutParams.setMargins(0, 0, 0, 0)
            }
            playerData.isMinimized -> {
                renderMinimized()
                cardLayoutParams.width = if (lastStripWidthPx > 0) lastStripWidthPx else LayoutParams.WRAP_CONTENT
                cardLayoutParams.setMargins(0, 0, 0, 0)
                cardLayoutParams.height = dp(MusicPlayerStyles.MINIMIZED_HEIGHT_DP)
                cardLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            else -> {
                val renderer = MusicPlayerNormalRenderer(context, callbacks, onUserAction)
                val naturalHeightPx = dp(renderer.heightDp)

                renderer.onHeightChanged = { newHeightDp ->
                    val newNaturalH = dp(newHeightDp)
                    savedVolumeExpanded = normalRenderer?.isVolumeCollapsed == false
                    // Update scaleContainer's natural height
                    scaleContainer?.let { sc ->
                        sc.layoutParams.height = newNaturalH
                        sc.requestLayout()
                    }
                    if (!hasCustomSize) {
                        cardLayoutParams.height = newNaturalH
                    } else {
                        // Maintain current scale, adjust card proportionally
                        val currentScale = cardLayoutParams.width.toFloat() / defaultWidthPx
                        cardLayoutParams.height = (newNaturalH * currentScale).toInt()
                    }
                    layoutParams = cardLayoutParams
                }

                // Create scale container at natural default dimensions
                val sc = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(defaultWidthPx, naturalHeightPx)
                    pivotX = 0f
                    pivotY = 0f
                }
                renderer.render(sc)
                // Allow scaled content to extend beyond scaleContainer's layout bounds
                clipChildren = false
                clipToPadding = false
                addView(sc)
                scaleContainer = sc
                normalRenderer = renderer
                cachedMediaBrowserDataSource?.let { renderer.setMediaBrowserDataSource(it) }

                touchHandler.minContentHeightPx = naturalHeightPx
                if (!hasCustomSize) {
                    cardLayoutParams.width = defaultWidthPx
                    cardLayoutParams.height = naturalHeightPx
                }
                cardLayoutParams.gravity = Gravity.BOTTOM or Gravity.END
                cardLayoutParams.rightMargin = dp(MusicPlayerStyles.MARGIN_DP)
                cardLayoutParams.bottomMargin = dp(MusicPlayerStyles.MARGIN_DP + 60)
            }
        }

        layoutParams = cardLayoutParams
        wireTouchHandler()
        updateViews()
    }

    /**
     * Render minimized player — a thin bottom bar matching the strip's speaker bar style.
     * Layout: [X] [Album Art] [Title (bold) | Artist] [Play/Pause] [Elapsed] [Up chevron]
     */
    private fun showCloseModal() {
        val activity = context as? android.app.Activity ?: return
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Music Player"
        dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text = "Would you like to:"

        val negBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative)
        val posBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive)
        val buttonsRow = negBtn.parent as? LinearLayout

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        buttonsRow?.orientation = LinearLayout.VERTICAL

        negBtn.apply {
            text = "Hide Player & Keep Playing"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f; marginEnd = 0; bottomMargin = dp(8)
            }
            setOnClickListener { dialog.dismiss(); callbacks.onHide() }
        }

        posBtn.apply {
            text = "Close Player & Stop Music"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f; marginStart = 0
            }
            setOnClickListener { dialog.dismiss(); callbacks.onStop() }
        }

        dialog.show()
    }

    private fun renderMinimized() {
        val isDark = MusicPlayerStyles.isDarkMode(context)
        val cornerR = dp(MusicPlayerStyles.CORNER_RADIUS_DP * 2).toFloat()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(8), 0, dp(8), 0)
            background = if (isDark) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(MusicPlayerStyles.HEADER_BG_DARK)
                    cornerRadius = cornerR
                }
            } else {
                android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(MusicPlayerStyles.BG_COLOR_LIGHT)
                        cornerRadius = cornerR
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x80000000.toInt())
                        cornerRadius = cornerR
                    }
                ))
            }
            elevation = dp(12).toFloat()
            if (isDark && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = 0x40FFFFFF.toInt()
                outlineSpotShadowColor = 0x50FFFFFF.toInt()
            }
        }

        // Tapping empty space on the bar restores to strip
        container.isClickable = true
        container.isFocusable = true
        container.setOnClickListener { callbacks.onStrip() }

        // X (close) button — shows modal: Hide / Close & Stop
        val closeBtnSize = dp(40)
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(closeBtnSize, closeBtnSize).apply { marginEnd = dp(8) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            setOnClickListener { showCloseModal() }
            container.addView(this)
        }

        // Album art
        val artSize = dp(MusicPlayerStyles.ART_SIZE_MINIMIZED_DP)
        albumArtMinimized = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply { marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
            val artCornerR = dp(4).toFloat()
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, artCornerR)
                }
            }
            isClickable = true
            setOnClickListener { callbacks.onStrip() }
        }
        container.addView(albumArtMinimized)

        // Track + Artist text (scrollable, fills available space)
        trackScrollViewMinimized = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onStrip() }
        }
        trackArtistTextMinimized = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        }
        trackScrollViewMinimized?.addView(trackArtistTextMinimized)
        container.addView(trackScrollViewMinimized)

        // Play/Pause button
        val miniPlaySize = dp(40)
        val miniPlayWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(miniPlaySize, miniPlaySize).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
        }
        playPauseButtonMinimized = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(miniPlaySize, miniPlaySize)
            background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                it.isClickable = false
                onUserAction()
                callbacks.onPlayPause()
                postDelayed({ it.isClickable = true }, 300)
            }
        }
        miniPlayWrapper.addView(playPauseButtonMinimized)
        playPauseSpinnerMinimized = MusicPlayerStyles.createReconnectingSpinner(context, miniPlaySize)
        miniPlayWrapper.addView(playPauseSpinnerMinimized)
        container.addView(miniPlayWrapper)

        // Elapsed time
        elapsedTextMinimized = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(32) }
            setTextColor(0xAAFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            text = ""
        }
        container.addView(elapsedTextMinimized)

        // Maximize (expand) button
        val upBtnSize = dp(40)
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(upBtnSize, upBtnSize).apply { marginEnd = dp(8) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_expand)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true; isFocusable = true
            setOnClickListener { callbacks.onMaximize() }
            container.addView(this)
        }

        // Up chevron (restore to strip) — rightmost
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(upBtnSize, upBtnSize)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_up)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true; isFocusable = true
            setOnClickListener { callbacks.onStrip() }
            container.addView(this)
        }

        addView(container)
    }

    private fun updateViews() {
        when {
            playerData.isStrip -> {
                stripRenderer?.idleSuggestion = firstRecentItem
                stripRenderer?.updateViews(playerData)
                // Album art: reconnecting → MA logo, idle → suggestion art, else → current track
                val isIdleConnected = !playerData.hasMedia && !playerData.isReconnecting && playerData.entityId.isNotEmpty()
                if (playerData.isReconnecting) {
                    showMusicAssistantLogo(stripRenderer?.getAlbumArtView())
                } else if (isIdleConnected && firstRecentItem?.imageUrl != null) {
                    loadAlbumArt(stripRenderer?.getAlbumArtView(), MusicPlayerStyles.STRIP_ART_SIZE_DP,
                        overrideUrl = firstRecentItem?.imageUrl)
                } else {
                    loadAlbumArt(stripRenderer?.getAlbumArtView(), MusicPlayerStyles.STRIP_ART_SIZE_DP)
                }
                // Scroll track text
                val stripTrack = playerData.trackName
                if (stripTrack != lastScrollTrackText) {
                    lastScrollTrackText = stripTrack
                    setupSmoothScroll(
                        stripRenderer?.trackScrollView,
                        stripRenderer?.trackText,
                        stripRenderer?.scrollAnimatorTrack
                    )
                }
                val stripArtist = playerData.artistName
                if (stripArtist != lastScrollArtistText) {
                    lastScrollArtistText = stripArtist
                    setupSmoothScroll(
                        stripRenderer?.artistScrollView,
                        stripRenderer?.artistText,
                        stripRenderer?.scrollAnimatorArtist
                    )
                }
            }
            playerData.isMaximized -> {
                maximizedRenderer?.idleSuggestion = firstRecentItem
                maximizedRenderer?.updateViews(playerData)
                val isIdleConnected = !playerData.hasMedia && !playerData.isReconnecting && playerData.entityId.isNotEmpty()
                if (playerData.isReconnecting) {
                    showMusicAssistantLogo(maximizedRenderer?.getAlbumArtView())
                } else if (isIdleConnected && firstRecentItem?.imageUrl != null) {
                    loadAlbumArt(maximizedRenderer?.getAlbumArtView(), MusicPlayerStyles.ART_SIZE_MAXIMIZED_DP,
                        overrideUrl = firstRecentItem?.imageUrl)
                } else {
                    loadAlbumArt(maximizedRenderer?.getAlbumArtView(), MusicPlayerStyles.ART_SIZE_MAXIMIZED_DP)
                }
            }
            playerData.isMinimized -> {
                val isIdleConnected = !playerData.hasMedia && !playerData.isReconnecting && playerData.entityId.isNotEmpty()
                val suggestion = if (isIdleConnected) firstRecentItem else null

                // Format as "Title (bold) | Artist"
                val miniSpannable = when {
                    playerData.isReconnecting -> android.text.SpannableString("Reconnecting • Music Assistant")
                    isIdleConnected && suggestion != null -> {
                        val name = suggestion.name
                        val sub = suggestion.artist ?: "Recently played"
                        val full = "$name  |  $sub"
                        android.text.SpannableString(full).apply {
                            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                0, name.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    isIdleConnected -> android.text.SpannableString("No music playing  |  Select a song from the media library")
                    else -> {
                        val title = playerData.trackName
                        val artist = playerData.artistName
                        val full = "$title  |  $artist"
                        android.text.SpannableString(full).apply {
                            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                0, title.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                trackArtistTextMinimized?.text = miniSpannable

                // Elapsed time
                elapsedTextMinimized?.text = if (playerData.hasMedia) playerData.formattedPosition else ""

                if (playerData.isReconnecting) {
                    playPauseButtonMinimized?.setImageDrawable(null)
                    playPauseButtonMinimized?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
                    playPauseButtonMinimized?.isClickable = false
                    playPauseSpinnerMinimized?.visibility = View.VISIBLE
                    showMusicAssistantLogo(albumArtMinimized)
                } else if (isIdleConnected) {
                    playPauseButtonMinimized?.setImageResource(com.dashieapp.Dashie.R.drawable.ic_play)
                    playPauseButtonMinimized?.setColorFilter(Color.WHITE)
                    if (suggestion != null) {
                        playPauseButtonMinimized?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
                        playPauseButtonMinimized?.isClickable = true
                        playPauseButtonMinimized?.setOnClickListener { callbacks.onPlayRecentItem?.invoke(suggestion.uri) }
                    } else {
                        playPauseButtonMinimized?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
                        playPauseButtonMinimized?.isClickable = false
                    }
                    playPauseSpinnerMinimized?.visibility = View.GONE
                    if (suggestion?.imageUrl != null) {
                        loadAlbumArt(albumArtMinimized, MusicPlayerStyles.ART_SIZE_MINIMIZED_DP, overrideUrl = suggestion.imageUrl)
                    } else {
                        loadAlbumArt(albumArtMinimized, MusicPlayerStyles.ART_SIZE_MINIMIZED_DP)
                    }
                } else {
                    playPauseButtonMinimized?.setImageResource(
                        if (playerData.isPlaying) com.dashieapp.Dashie.R.drawable.ic_pause
                        else com.dashieapp.Dashie.R.drawable.ic_play
                    )
                    playPauseButtonMinimized?.setColorFilter(Color.WHITE)
                    playPauseButtonMinimized?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
                    playPauseButtonMinimized?.isClickable = true
                    playPauseButtonMinimized?.setOnClickListener {
                        it.isClickable = false
                        onUserAction()
                        callbacks.onPlayPause()
                        postDelayed({ it.isClickable = true }, 300)
                    }
                    playPauseSpinnerMinimized?.visibility = View.GONE
                    loadAlbumArt(albumArtMinimized, MusicPlayerStyles.ART_SIZE_MINIMIZED_DP)
                }
                // Only re-setup scroll when track text changes (avoids competing animators)
                val miniText = miniSpannable.toString()
                if (miniText != lastScrollTrackText) {
                    lastScrollTrackText = miniText
                    setupSmoothScroll(trackScrollViewMinimized, trackArtistTextMinimized, scrollAnimatorMinimized)
                }
            }
            else -> {
                normalRenderer?.idleSuggestion = firstRecentItem
                normalRenderer?.updateViews(playerData)
                // In reconnecting state, show MA logo; in idle state, show suggestion art; otherwise load current track art
                val isIdleConnected = !playerData.hasMedia && !playerData.isReconnecting && playerData.entityId.isNotEmpty()
                if (playerData.isReconnecting) {
                    showMusicAssistantLogo(normalRenderer?.getAlbumArtView())
                } else if (isIdleConnected && firstRecentItem?.imageUrl != null) {
                    loadAlbumArt(normalRenderer?.getAlbumArtView(), MusicPlayerStyles.ART_SIZE_NORMAL_DP,
                        overrideUrl = firstRecentItem?.imageUrl)
                } else {
                    loadAlbumArt(normalRenderer?.getAlbumArtView(), MusicPlayerStyles.ART_SIZE_NORMAL_DP)
                }
                // Only re-setup scroll when track text changes (avoids competing animators)
                val normalText = playerData.trackName
                if (normalText != lastScrollTrackText) {
                    lastScrollTrackText = normalText
                    setupSmoothScroll(
                        normalRenderer?.trackScrollViewNormal,
                        normalRenderer?.trackTextView,
                        normalRenderer?.scrollAnimatorNormal
                    )
                }
                val normalArtist = playerData.artistName
                if (normalArtist != lastScrollArtistText) {
                    lastScrollArtistText = normalArtist
                    setupSmoothScroll(
                        normalRenderer?.artistScrollViewNormal,
                        normalRenderer?.artistTextView,
                        normalRenderer?.scrollAnimatorArtist
                    )
                }
            }
        }
    }

    private fun wireTouchHandler() {
        touchHandler.buttonProvider = {
            val rendererButtons = when {
                playerData.isStrip -> stripRenderer?.getClickableButtons() ?: emptyList()
                playerData.isMaximized -> maximizedRenderer?.getClickableButtons() ?: emptyList()
                playerData.isMinimized -> listOfNotNull(playPauseButtonMinimized)
                else -> normalRenderer?.getClickableButtons() ?: emptyList()
            }
            rendererButtons
        }
        touchHandler.volumeBoundsProvider = {
            normalRenderer?.getVolumeSectionBounds(this)?.scaledByContent()
        }
        touchHandler.recentlyPlayedBoundsProvider = {
            normalRenderer?.getRecentlyPlayedBounds(this)?.scaledByContent()
        }
        touchHandler.speakerRowBoundsProvider = {
            normalRenderer?.getSpeakerRowBounds(this)?.scaledByContent()
        }
        // Proportional resize: maintain aspect ratio of natural content
        touchHandler.resizeConstraintProvider = { proposedW, proposedH ->
            val sc = scaleContainer
            if (sc != null) {
                val naturalW = sc.layoutParams.width
                val naturalH = sc.layoutParams.height
                val scaleX = proposedW.toFloat() / naturalW
                val scaleY = proposedH.toFloat() / naturalH
                val scale = maxOf(scaleX, scaleY, 1f)
                Pair((naturalW * scale).toInt(), (naturalH * scale).toInt())
            } else {
                Pair(proposedW, proposedH)
            }
        }
        touchHandler.onSizeChanged = { newWidth, newHeight ->
            hasCustomSize = true
            cardLayoutParams.width = newWidth
            cardLayoutParams.height = newHeight
            Log.d(TAG, "Resize complete: ${newWidth}x${newHeight}, scale=${scaleContainer?.scaleX}, hasCustomSize=true")
        }
    }

    /** Adjust a bounds Rect's height by the current content scale (for touch passthrough). */
    private fun Rect.scaledByContent(): Rect {
        val s = scaleContainer?.scaleY ?: 1f
        if (s <= 1f) return this
        val scaledH = ((bottom - top) * s).toInt()
        return Rect(left, top, right, top + scaledH)
    }

    fun updateRecentlyPlayed(data: RecentlyPlayedData) {
        firstRecentItem = data.items.firstOrNull()
        cachedRecentlyPlayed = data
        normalRenderer?.updateRecentlyPlayed(data)
        maximizedRenderer?.updateRecentlyPlayed(listOf(data))
        stripRenderer?.updateRecentlyPlayed(data)
        // If idle-connected, refresh views to show the first recently played item
        if (!playerData.hasMedia && !playerData.isReconnecting && playerData.entityId.isNotEmpty()) {
            updateViews()
        }
    }

    /** Set the data source for the media browser panel's library browsing. */
    fun setMediaBrowserDataSource(ds: MediaBrowserDataSource) {
        cachedMediaBrowserDataSource = ds
        normalRenderer?.setMediaBrowserDataSource(ds)
        stripRenderer?.setMediaBrowserDataSource(ds)
        maximizedRenderer?.setMediaBrowserDataSource(ds)
    }

    fun setVolumeCoordinator(coordinator: MusicStateCoordinator?) {
        cachedVolumeCoordinator = coordinator
        stripRenderer?.volumeCoordinator = coordinator
        stripRenderer?.refreshVolumeDisplay()
        stripRenderer?.refreshVolumeToggle()
        // TODO: maximizedRenderer volume coordinator
    }

    fun updateSpeakerDrawer(speakers: List<SpeakerGroupDrawer.SpeakerInfo>, groups: List<SpeakerGroupDrawer.GroupInfo> = emptyList(), thisDeviceId: String = "", currentTargetId: String = "", defaultEntityId: String = "", coordinator: MusicStateCoordinator? = null) {
        // Pass coordinator to drawer for shared volume state
        if (coordinator != null) {
            cachedVolumeCoordinator = coordinator
            normalRenderer?.speakerDrawer?.coordinator = coordinator
            stripRenderer?.speakerDrawer?.coordinator = coordinator
            stripRenderer?.volumeCoordinator = coordinator
        }
        // Update shared speaker group state in coordinator (every call, not just first)
        cachedVolumeCoordinator?.updateSpeakerGroupState(groups, speakers, currentTargetId, thisDeviceId)
        normalRenderer?.updateSpeakerDrawer(speakers, groups, thisDeviceId, currentTargetId, defaultEntityId)
        stripRenderer?.updateSpeakerDrawer(speakers, groups, thisDeviceId, currentTargetId, defaultEntityId)
        maximizedRenderer?.updateSpeakerDrawer(speakers, groups, thisDeviceId, currentTargetId, defaultEntityId)
    }

    fun updateSpeakerVolume(playerId: String, volumePercent: Int) {
        normalRenderer?.updateSpeakerVolume(playerId, volumePercent)
        stripRenderer?.updateSpeakerVolume(playerId, volumePercent)
        maximizedRenderer?.updateSpeakerVolume(playerId, volumePercent)
    }

    fun updateSpeakerDrawerCurrentTarget(targetPlayerId: String) {
        cachedVolumeCoordinator?.updateSpeakerGroupTarget(targetPlayerId)
        normalRenderer?.updateSpeakerDrawerCurrentTarget(targetPlayerId)
        stripRenderer?.updateSpeakerDrawerCurrentTarget(targetPlayerId)
        maximizedRenderer?.updateSpeakerDrawerCurrentTarget(targetPlayerId)
    }

    fun getOfflineGroupMembers(targetEntityId: String = ""): List<String> {
        return stripRenderer?.speakerDrawer?.getOfflineGroupMembers(targetEntityId)
            ?: normalRenderer?.speakerDrawer?.getOfflineGroupMembers(targetEntityId)
            ?: emptyList()
    }

    fun findSpeakerName(playerId: String): String? {
        return stripRenderer?.speakerDrawer?.findSpeakerName(playerId)
            ?: normalRenderer?.speakerDrawer?.findSpeakerName(playerId)
    }

    fun configureVisibilityStore(haBaseUrl: String, haAuthToken: String) {
        normalRenderer?.configureVisibilityStore(haBaseUrl, haAuthToken)
        stripRenderer?.configureVisibilityStore(haBaseUrl, haAuthToken)
    }

    private fun showMusicAssistantLogo(imageView: ImageView?) {
        imageView ?: return
        lastAlbumArtUrl = "__ma_logo__"
        val isDark = MusicPlayerStyles.isDarkMode(context)
        imageView.setImageResource(
            if (isDark) com.dashieapp.Dashie.R.drawable.ic_music_assistant_dark
            else com.dashieapp.Dashie.R.drawable.ic_music_assistant_light
        )
        imageView.clearColorFilter()
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setBackgroundColor(if (isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun loadAlbumArt(imageView: ImageView?, sizeDp: Int, overrideUrl: String? = null) {
        imageView ?: return
        val artUrl = overrideUrl ?: playerData.albumArtUrl
        if (artUrl == lastAlbumArtUrl) return
        lastAlbumArtUrl = artUrl

        if (artUrl.isNullOrBlank()) {
            imageView.setImageResource(android.R.drawable.ic_media_play)
            imageView.setColorFilter(MusicPlayerStyles.textSecondary(context))
            imageView.scaleType = ImageView.ScaleType.CENTER
            return
        }

        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.clearColorFilter()
        val request = coil.request.ImageRequest.Builder(context)
            .data(artUrl).target(imageView)
            .placeholder(android.R.drawable.ic_media_play)
            .error(android.R.drawable.ic_media_play)
            .crossfade(150).build()
        coil.ImageLoader(context).enqueue(request)
    }

    private fun setupSmoothScroll(
        scrollView: HorizontalScrollView?,
        textView: TextView?,
        existingAnimator: ValueAnimator?
    ): ValueAnimator? {
        scrollView ?: return null
        textView ?: return null

        // Cancel all existing scroll animators
        existingAnimator?.cancel()
        scrollAnimatorMinimized?.cancel()
        scrollAnimatorMinimized = null
        normalRenderer?.scrollAnimatorNormal?.cancel()
        normalRenderer?.scrollAnimatorNormal = null
        normalRenderer?.scrollAnimatorArtist?.cancel()
        normalRenderer?.scrollAnimatorArtist = null
        stripRenderer?.scrollAnimatorTrack?.cancel()
        stripRenderer?.scrollAnimatorTrack = null
        stripRenderer?.scrollAnimatorArtist?.cancel()
        stripRenderer?.scrollAnimatorArtist = null

        scrollView.scrollTo(0, 0)

        // Remove any pending listener for this scrollView to prevent orphaned animators
        pendingScrollListeners.remove(scrollView)?.let {
            scrollView.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }

        val listener = object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                pendingScrollListeners.remove(scrollView)
                val text = textView.text?.toString() ?: ""
                val textWidth = textView.paint.measureText(text).toInt()
                // Use visible width (subtract padding) to correctly detect text overflow
                val visibleWidth = scrollView.width - scrollView.paddingLeft - scrollView.paddingRight
                if (visibleWidth <= 0) return
                val overflow = textWidth - visibleWidth
                if (overflow <= 0) {
                    // Short text — no scroll needed, ensure scroll is reset
                    scrollView.scrollTo(0, 0)
                    return
                }

                val scrollDuration = (overflow / 30f * 1000).toLong().coerceIn(2000L, 10000L)
                val pauseDuration = 2500L

                val animator = ValueAnimator.ofInt(0, overflow).apply {
                    duration = scrollDuration
                    startDelay = pauseDuration
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { scrollView.scrollTo(it.animatedValue as Int, 0) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationRepeat(animation: android.animation.Animator) {
                            animation.pause()
                            scrollView.postDelayed({
                                if (animation.isPaused) animation.resume()
                            }, pauseDuration)
                        }
                    })
                }
                animator.start()

                when (scrollView) {
                    trackScrollViewMinimized -> scrollAnimatorMinimized = animator
                    normalRenderer?.trackScrollViewNormal -> normalRenderer?.scrollAnimatorNormal = animator
                    normalRenderer?.artistScrollViewNormal -> normalRenderer?.scrollAnimatorArtist = animator
                    stripRenderer?.trackScrollView -> stripRenderer?.scrollAnimatorTrack = animator
                    stripRenderer?.artistScrollView -> stripRenderer?.scrollAnimatorArtist = animator
                }
            }
        }
        pendingScrollListeners[scrollView] = listener
        scrollView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        return null
    }

    /**
     * Save drag position before screensaver panel migration.
     * Call before removeView to preserve the overlay position.
     */
    fun savePositionBeforePanelMigration() {
        touchHandler.savePositionForMode(isMinimized = playerData.isMinimized)
    }

    /**
     * Restore drag position after returning from screensaver panel.
     * Resets touch state so gravity-based layout works, then restores saved position.
     */
    fun restorePositionAfterPanelMigration() {
        touchHandler.resetPosition()
        if (playerData.isMaximized || playerData.isStrip || playerData.isMinimized) return  // Fixed layout modes, no position to restore
        post {
            val isMin = playerData.isMinimized
            if (touchHandler.hasSavedPosition(isMin)) {
                touchHandler.restoreSavedPosition(isMin, cardLayoutParams)
            }
        }
    }

    /**
     * Re-render the card to pick up style changes (e.g., forced dark mode on screensaver panel).
     * Preserves volume-expanded state across the re-render.
     */
    fun forceRestyle() {
        Log.i(TAG, "forceRestyle: forceDarkMode=${MusicPlayerStyles.forceDarkMode}, " +
            "isMaximized=${playerData.isMaximized}, isMinimized=${playerData.isMinimized}")
        // Save volume state from the current renderer OR use the persistent saved state
        // (normalRenderer is null when returning from maximized/screensaver mode)
        val wasVolumeExpanded = normalRenderer?.isVolumeCollapsed?.let { !it } ?: savedVolumeExpanded
        render()
        if (wasVolumeExpanded) {
            normalRenderer?.expandVolumeSection()
        }
        // Persist for next forceRestyle (survives maximized mode where normalRenderer is null)
        savedVolumeExpanded = wasVolumeExpanded
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Configuration changed - re-rendering for theme update")
        // Save UI state before re-render
        val wasVolumeExpanded = normalRenderer?.isVolumeCollapsed?.let { !it } ?: savedVolumeExpanded
        touchHandler.savePositionForMode(isMinimized = playerData.isMinimized)
        render()
        // Restore expanded state after re-render
        if (wasVolumeExpanded) {
            normalRenderer?.expandVolumeSection()
        }
        // Only restore drag position for floating modes (normal)
        // Strip and minimized bar are fixed-position bottom bars
        if (!playerData.isStrip && !playerData.isMinimized && !playerData.isMaximized) {
            post {
                val isMin = playerData.isMinimized
                if (touchHandler.hasSavedPosition(isMin)) {
                    touchHandler.restoreSavedPosition(isMin, cardLayoutParams)
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Compute and apply uniform scale to content container
        val sc = scaleContainer ?: return
        val naturalW = sc.layoutParams.width
        val naturalH = sc.layoutParams.height
        if (naturalW > 0 && naturalH > 0) {
            val scale = minOf(w.toFloat() / naturalW, h.toFloat() / naturalH).coerceAtLeast(1f)
            sc.scaleX = scale
            sc.scaleY = scale
            touchHandler.contentScale = scale
        }
    }

    fun setPosition(x: Int, y: Int) {
        touchHandler.setPosition(x, y, cardLayoutParams)
    }

    fun setSavedPosition(isMinimized: Boolean, x: Int, y: Int) {
        touchHandler.setSavedPosition(isMinimized, x, y)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Strip mode: forward to children, always consume
        if (playerData.isStrip) {
            super.dispatchTouchEvent(event)
            return true
        }
        // Minimized mode: route through touch handler for drag support
        // (tap = expand to strip, drag = reposition)
        if (playerData.isMinimized) {
            val result = touchHandler.handleTouchEvent(
                event, isMaximized = false, isMinimized = true, cardLayoutParams)
            if (result == true) return true
            super.dispatchTouchEvent(event)
            return true
        }
        val result = touchHandler.handleTouchEvent(
            event, playerData.isMaximized, playerData.isMinimized, cardLayoutParams)
        return result ?: super.dispatchTouchEvent(event)
    }

    /** Animate entrance: slide up from below screen. Called after addView. */
    fun animateEntrance() {
        visibility = View.INVISIBLE
        post {
            val slideDistance = height.toFloat().coerceAtLeast(dp(200).toFloat())
            translationY = slideDistance
            visibility = View.VISIBLE
            animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** Animate slide down off-screen and then invoke the close callback. */
    fun animateSlideDownAndClose(onClosed: () -> Unit) {
        if (playerData.isStrip || playerData.isMinimized) {
            val slideDistance = height.toFloat().coerceAtLeast(dp(200).toFloat())
            animate()
                .translationY(slideDistance)
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    translationY = 0f
                    onClosed()
                }
                .start()
        } else {
            onClosed()
        }
    }

    /** Show a profile switcher dialog listing available MA users */
    private fun showProfileSwitcher() {
        val mgr = musicProfileManager ?: return

        // Try linked family members first, fall back to all MA users
        val linked = mgr.getLinkedMembers()
        if (linked.isNotEmpty()) {
            val activeProfile = mgr.getActiveProfile()
            val names = linked.map { member ->
                val provider = member.maUserId?.let { maId -> mgr.getMaUserName(maId) }
                if (provider != null) "${member.displayName} ($provider)" else member.displayName
            }.toTypedArray()
            val currentIndex = linked.indexOfFirst { it.displayName == activeProfile?.memberName }.coerceAtLeast(0)

            androidx.appcompat.app.AlertDialog.Builder(context, com.dashieapp.Dashie.R.style.Theme_Dashie_Dialog)
                .setTitle("Switch Music Profile")
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    dialog.dismiss()
                    mgr.setActiveProfileId(linked[which].id)
                    maximizedRenderer?.refreshActiveProfile()
                    stripRenderer?.refreshActiveProfile()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // No linked family members — show raw MA users as fallback
        val maUserNames = mgr.getMaUserNames()
        if (maUserNames.isEmpty()) {
            android.widget.Toast.makeText(context, "No music profiles available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val entries = maUserNames.entries.toList()
        val names = entries.map { it.value }.toTypedArray()
        val activeProfile = mgr.getActiveProfile()
        val currentIndex = entries.indexOfFirst { it.value == activeProfile?.memberName }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(context, com.dashieapp.Dashie.R.style.Theme_Dashie_Dialog)
            .setTitle("Switch Music Profile")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                dialog.dismiss()
                val selectedUserId = entries[which].key
                mgr.setActiveProfileId(selectedUserId)
                maximizedRenderer?.refreshActiveProfile()
                stripRenderer?.refreshActiveProfile()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun destroy() {
        Log.d(TAG, "Destroying music player card")
        scrollAnimatorMinimized?.cancel()
        scrollAnimatorMinimized = null
        pendingScrollListeners.clear()
        normalRenderer?.destroy()
        maximizedRenderer?.destroy()
        stripRenderer?.destroy()
        removeAllViews()
    }
}

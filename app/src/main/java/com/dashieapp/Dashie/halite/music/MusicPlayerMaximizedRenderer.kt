package com.dashieapp.Dashie.halite.music

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Renders the maximized (full-screen) music player view.
 * Delegates player content to MaximizedPlayerControls.
 * Manages overlay buttons (close, minimize, normalize)
 * and split-view toggling between MusicExplorerPanel and SpeakerGroupDrawer.
 */
class MusicPlayerMaximizedRenderer(
    private val context: android.content.Context,
    private val overlayContainer: ViewGroup,
    private val callbacks: MusicPlayerCallbacks,
    private val onUserAction: () -> Unit
) {

    companion object {
        private const val TAG = "MusicPlayerMaxRndr"
    }

    private enum class SplitPanelType { NONE, MEDIA_BROWSER, SPEAKER_DRAWER }

    private var playerControls: MaximizedPlayerControls? = null
    private var explorerPanel: MusicExplorerPanel? = null
    private var speakerDrawer: SpeakerGroupDrawer? = null
    private var closeButton: ImageButton? = null
    private var minimizeButton: ImageButton? = null
    private var normalButton: ImageButton? = null
    private var speakerStripName: android.widget.TextView? = null
    private var speakerStripIcon: ImageView? = null
    private var speakerStripBadge: android.widget.TextView? = null
    private var mediaProfileIcon: ImageView? = null
    private var mediaProfileText: android.widget.TextView? = null
    private var cachedMusicProfile: MusicProfile? = null
    /** Coordinator for shared speaker group state. */
    var volumeCoordinator: MusicStateCoordinator? = null
    var isSplitView = false
    private var splitPanelType = SplitPanelType.NONE
    private var currentParent: FrameLayout? = null
    private var splitPlayerWrapper: FrameLayout? = null
    // In narrow-portrait split view the bottom dock uses MusicPlayerNormalRenderer
    // (the compact horizontal card) instead of MaximizedPlayerControls.
    private var splitNormalRenderer: MusicPlayerNormalRenderer? = null
    private var lastData: MusicPlayerData? = null
    private var cachedRecentlyPlayed: List<RecentlyPlayedData> = emptyList()
    private var cachedSpeakers: List<SpeakerGroupDrawer.SpeakerInfo> = emptyList()
    private var cachedGroups: List<SpeakerGroupDrawer.GroupInfo> = emptyList()
    private var cachedThisDeviceId = ""
    private var cachedCurrentTargetId = ""
    private var cachedDefaultEntityId = ""
    var idleSuggestion: RecentlyPlayedItem? = null
        set(value) {
            field = value
            playerControls?.idleSuggestion = value
            splitNormalRenderer?.idleSuggestion = value
        }
    var onExplorerToggled: (() -> Unit)? = null
    var musicProfileManager: MusicProfileManager? = null
    var onProfileSwitchRequested: (() -> Unit)? = null

    private val augmentedCallbacks = callbacks.copy(
        onExplorerToggle = { toggleSplitPanel(SplitPanelType.MEDIA_BROWSER) },
        onSpeakerClicked = { toggleSplitPanel(SplitPanelType.SPEAKER_DRAWER) }
    )

    private fun dp(dp: Int) = MusicPlayerStyles.dpToPx(context, dp)

    fun render(parent: FrameLayout) {
        currentParent = parent
        val isOnPanel = MusicPlayerStyles.forceDarkMode

        if (isSplitView && !isOnPanel) {
            renderSplitView(parent)
        } else {
            renderFullView(parent)
        }

        renderOverlayButtons(parent, isOnPanel)

        // Apply speaker group indicator from coordinator (may already have data)
        updateSpeakerStripIndicator()
        val coord = volumeCoordinator
        if (coord != null) {
            playerControls?.setGroupActive(coord.isCurrentTargetGroup, coord.speakerCount)
        }
    }

    private fun renderFullView(parent: FrameLayout) {
        val screenWidth = overlayContainer.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val screenHeight = overlayContainer.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        val isOnPanel = MusicPlayerStyles.forceDarkMode

        if (isOnPanel) {
            android.util.Log.i(TAG, "renderFullView ON PANEL: parent=${parent.width}x${parent.height}, " +
                "screen=${screenWidth}x${screenHeight}, overlay=${overlayContainer.width}x${overlayContainer.height}, " +
                "displayMetrics=${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
        }

        val panelWidth = if (isOnPanel) parent.width.takeIf { it > 0 } else null
        val isTinyScreen = screenHeight < dp(500)
        val showStrip = isOnPanel && !isTinyScreen

        playerControls = MaximizedPlayerControls(
            context = context,
            availableWidth = screenWidth,
            availableHeight = screenHeight,
            callbacks = augmentedCallbacks,
            onUserAction = onUserAction,
            constrainedWidth = panelWidth,
            showSpeakerIndicator = !isOnPanel,
            showPlaylistButton = !isOnPanel,
            reserveBottomDp = if (showStrip) 44 else 0
        ).also {
            it.volumeCoordinator = volumeCoordinator
            it.idleSuggestion = idleSuggestion
            it.setPlaylistButtonActive(isSplitView && splitPanelType == SplitPanelType.MEDIA_BROWSER)
            it.setSpeakerButtonActive(isSplitView && splitPanelType == SplitPanelType.SPEAKER_DRAWER)
        }

        val container = playerControls!!.build().apply {
            background = MusicPlayerStyles.createRoundedBackground(context, opaque = true)
        }
        parent.addView(container)
    }

    private fun renderSplitView(parent: FrameLayout) {
        val screenWidth = overlayContainer.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val screenHeight = overlayContainer.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        val screenWidthDp = (context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density).toInt()
        // Narrow portrait (Fire tablet portrait ~601dp, Samsung SM-X200 portrait):
        // stack the media browser / speaker drawer on top 2/3 and a compact player
        // card on the bottom 1/3 instead of splitting left/right.
        val isNarrowPortrait = screenWidthDp < 750

        val playerWidth = if (isNarrowPortrait) screenWidth else screenWidth / 3
        val explorerWidth = if (isNarrowPortrait) screenWidth else screenWidth - playerWidth
        // Narrow portrait: 75% top panel, 25% bottom player dock.
        val playerHeight = if (isNarrowPortrait) screenHeight / 4 else screenHeight
        val explorerHeight = if (isNarrowPortrait) screenHeight - playerHeight else screenHeight

        val splitContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = MusicPlayerStyles.createRoundedBackground(context, opaque = true)
        }

        // Left (2/3): media browser or speaker drawer
        val leftPanel = when (splitPanelType) {
            SplitPanelType.MEDIA_BROWSER -> {
                val explorerScreenDp = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density).toInt()
                // Narrow portrait uses strip-player default scale (1.0x) so
                // speaker/media names aren't truncated. Wide devices use 1.5x.
                val explorerScale = if (isNarrowPortrait || explorerScreenDp < 400) 1.0f else 1.5f
                explorerPanel = MusicExplorerPanel(
                    context = context,
                    width = explorerWidth,
                    height = explorerHeight,
                    sizeMultiplier = explorerScale,
                    onPlayItem = { uri -> callbacks.onPlayRecentItem?.invoke(uri) },
                    onClose = { toggleSplitPanel(SplitPanelType.MEDIA_BROWSER) },
                    onProfileTapped = { onProfileSwitchRequested?.invoke() }
                )
                cachedDataSource?.let { explorerPanel!!.setDataSource(it) }
                explorerPanel!!.musicProfileManager = musicProfileManager
                explorerPanel!!.onProfileSwitched = { profile ->
                    cachedMusicProfile = profile
                    updateMediaProfileStrip()
                }
                val panel = explorerPanel!!.build()
                // Set profile AFTER build so the sidebar exists
                val mgr = musicProfileManager
                val cachedProfile = mgr?.getActiveProfile()
                explorerPanel!!.setActiveProfile(cachedProfile)
                if (cachedProfile == null && mgr != null) {
                    Thread {
                        mgr.ensureDataLoaded()
                        val profile = mgr.getActiveProfile()
                        if (profile != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                explorerPanel?.setActiveProfile(profile)
                            }
                        }
                    }.start()
                }
                panel
            }
            SplitPanelType.SPEAKER_DRAWER -> {
                // Scale down on small screens (Echo Show 480px height) and in
                // narrow portrait so speaker names aren't truncated — match
                // the strip player drawer's default 1.0x scale there.
                val screenHeightDp = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density).toInt()
                val drawerScale = if (isNarrowPortrait || screenHeightDp < 400) 1.0f else 1.5f
                speakerDrawer = SpeakerGroupDrawer(
                    context = context,
                    forceDarkColors = true,
                    sizeMultiplier = drawerScale,
                    onClose = { toggleSplitPanel(SplitPanelType.SPEAKER_DRAWER) },
                    onSpeakerToggle = { playerId, join -> callbacks.onSpeakerJoin?.invoke(playerId, join) },
                    onSpeakerVolumeChange = { playerId, pct -> callbacks.onSpeakerVolumeChange?.invoke(playerId, pct) },
                    onSpeakerMuteToggle = { playerId, muted -> callbacks.onSpeakerMuteToggle?.invoke(playerId, muted) },
                    onSpeakerBarTextChanged = { text -> speakerStripName?.text = text },
                    onTransferQueue = { id, name -> callbacks.onTransferQueue?.invoke(id, name) },
                    onClearQueue = { id -> callbacks.onClearQueue?.invoke(id) },
                    onGroupVolumeChange = { pct -> callbacks.onGroupVolumeChange?.invoke(pct) },
                    onGroupMuteToggle = { muted -> callbacks.onGroupMuteToggle?.invoke(muted) }
                ).also {
                    if (cachedThisDeviceId.isNotEmpty()) it.thisDevicePlayerId = cachedThisDeviceId
                    if (cachedDefaultEntityId.isNotEmpty()) it.defaultEntityId = cachedDefaultEntityId
                }
                speakerDrawer!!.view.apply {
                    visibility = android.view.View.VISIBLE
                    setBackgroundColor(0xFF1A1A1A.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                // Wrap in a vertical layout: title row + drawer content
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF1A1A1A.toInt())

                    // Title row: "Speaker Selector" + orange X
                    val titleRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(24), dp(24), dp(16), dp(12))
                    }
                    titleRow.addView(android.widget.TextView(context).apply {
                        text = "Speaker Selector"
                        setTextColor(MusicPlayerStyles.TEXT_PRIMARY_DARK)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 23f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    val closeSize = dp(28)
                    titleRow.addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(closeSize, closeSize).apply {
                            marginStart = dp(12)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(MusicPlayerStyles.ACCENT_COLOR)
                        }
                        setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
                        setColorFilter(android.graphics.Color.WHITE)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(5), dp(5), dp(5), dp(5))
                        isClickable = true; isFocusable = true
                        setOnClickListener { toggleSplitPanel(SplitPanelType.SPEAKER_DRAWER) }
                    })
                    addView(titleRow)
                    addView(speakerDrawer!!.view)
                }
            }
            SplitPanelType.NONE -> FrameLayout(context)
        }
        // Build the left/top panel as a vertical layout: content (fills) + sticky icon row (bottom).
        // In narrow portrait this panel occupies the top 2/3 at full width; in wide
        // mode it occupies the left 2/3 at full height.
        val leftColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (isNarrowPortrait) {
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, explorerHeight).apply {
                    gravity = Gravity.TOP
                }
            } else {
                FrameLayout.LayoutParams(explorerWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                }
            }
        }
        leftPanel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        leftColumn.addView(leftPanel)

        // Sticky bottom icon row
        val iconSize = dp(48)
        val iconPad = dp(7)
        val mediaColor = if (splitPanelType == SplitPanelType.MEDIA_BROWSER) MusicPlayerStyles.ACCENT_COLOR
            else MusicPlayerStyles.TEXT_SECONDARY_DARK
        val speakerColor = if (splitPanelType == SplitPanelType.SPEAKER_DRAWER) MusicPlayerStyles.ACCENT_COLOR
            else MusicPlayerStyles.TEXT_SECONDARY_DARK
        val iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        iconRow.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(12) }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
            setColorFilter(mediaColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleSplitPanel(SplitPanelType.MEDIA_BROWSER) }
        })
        iconRow.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_box_speaker)
            setColorFilter(speakerColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleSplitPanel(SplitPanelType.SPEAKER_DRAWER) }
        })
        leftColumn.addView(iconRow)

        splitContainer.addView(leftColumn)

        if (isNarrowPortrait) {
            // Narrow portrait: compact card (MusicPlayerNormalRenderer) at bottom.
            // forceDarkMode=true so the renderer hides its internal close/maximize
            // overlay buttons — we're already inside the maximized overlay.
            splitPlayerWrapper = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, playerHeight
                ).apply { gravity = Gravity.BOTTOM }
            }
            val prevDarkMode = MusicPlayerStyles.forceDarkMode
            MusicPlayerStyles.forceDarkMode = true
            try {
                splitNormalRenderer = MusicPlayerNormalRenderer(
                    context, augmentedCallbacks, onUserAction
                ).also {
                    it.idleSuggestion = idleSuggestion
                    it.render(splitPlayerWrapper!!)
                }
                // MusicPlayerNormalRenderer.render() adds its wrapper with
                // MATCH_PARENT × MATCH_PARENT layoutParams. That makes the card
                // fill the entire 25% dock and its content (album + controls +
                // speaker row) stacks from the top, leaving empty space at the
                // bottom — and partially hides the speaker row under the system
                // bar. Replace the card's layoutParams with WRAP_CONTENT height
                // + CENTER_VERTICAL so the wrapper only takes its natural height
                // and sits in the vertical middle of the dock.
                val card = splitPlayerWrapper!!.getChildAt(0)
                if (card != null) {
                    card.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER_VERTICAL }
                    // Drop the card's own rounded background (which has a 1dp
                    // border stroke that shows as a line above the player).
                    // The split container already supplies an opaque dark bg.
                    card.background = null
                }
                lastData?.let { splitNormalRenderer?.updateViews(it) }
            } finally {
                MusicPlayerStyles.forceDarkMode = prevDarkMode
            }
            splitContainer.addView(splitPlayerWrapper)
        } else {
            // Wide mode: player controls on the right (existing behavior).
            val isTinyScreen = screenHeight < dp(500)
            val splitShowStrip = !isTinyScreen
            playerControls = MaximizedPlayerControls(
                context = context,
                availableWidth = screenWidth,
                availableHeight = screenHeight,
                callbacks = augmentedCallbacks,
                onUserAction = onUserAction,
                constrainedWidth = playerWidth,
                showSpeakerIndicator = false,
                showPlaylistButton = false,
                reserveBottomDp = if (splitShowStrip) 44 else 0
            ).also {
                it.volumeCoordinator = volumeCoordinator
                it.idleSuggestion = idleSuggestion
            }

            splitPlayerWrapper = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(playerWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                }
            }
            splitPlayerWrapper!!.addView(playerControls!!.build().apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })

            val currentPanelType = splitPanelType
            val gestureDetector = android.view.GestureDetector(context,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                                         velocityX: Float, velocityY: Float): Boolean {
                        if (e1 != null && (e1.x - e2.x) > dp(50) && kotlin.math.abs(velocityX) > 200) {
                            toggleSplitPanel(currentPanelType)
                            return true
                        }
                        return false
                    }
                    override fun onDown(e: android.view.MotionEvent): Boolean = true
                })
            splitPlayerWrapper!!.isClickable = true
            splitPlayerWrapper!!.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

            splitContainer.addView(splitPlayerWrapper)
        }

        parent.addView(splitContainer)
    }

    private fun renderOverlayButtons(parent: FrameLayout, isOnPanel: Boolean) {
        val screenHeight = overlayContainer.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        val isTinyScreen = screenHeight < dp(500)
        val isLargeScreen = screenHeight > dp(900)
        val showStrip = (isOnPanel || isSplitView) && !isTinyScreen

        if (showStrip) {
            val stripPadV = if (isLargeScreen) dp(14) else dp(10)
            val stripPadH = if (isLargeScreen) dp(20) else dp(16)
            val textSize = if (isLargeScreen) 20f else 16f
            val stripIconSize = if (isLargeScreen) dp(32) else dp(24)

            val strip = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF2A2A2A.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.BOTTOM }
                setPadding(stripPadH, stripPadV, stripPadH, stripPadV)
                elevation = dp(4).toFloat()
            }

            // Left: [provider logo] [Profile's Music] — opens media browser
            val mediaArea = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                setOnClickListener { toggleSplitPanel(SplitPanelType.MEDIA_BROWSER) }
            }
            mediaProfileIcon = ImageView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(stripIconSize, stripIconSize).apply {
                    marginEnd = if (isLargeScreen) dp(8) else dp(6)
                }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                scaleType = ImageView.ScaleType.FIT_CENTER
                mediaArea.addView(this)
            }
            mediaProfileText = android.widget.TextView(context).apply {
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = "Music"
                mediaArea.addView(this)
            }
            strip.addView(mediaArea)

            // Spacer
            strip.addView(android.view.View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
            })

            // Right: [speaker icon] [speaker name] [count badge] — opens speaker drawer
            val speakerArea = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                setOnClickListener { toggleSplitPanel(SplitPanelType.SPEAKER_DRAWER) }
            }
            speakerStripIcon = ImageView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(stripIconSize, stripIconSize).apply {
                    marginEnd = if (isLargeScreen) dp(8) else dp(6)
                }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_box_speaker)
                setColorFilter(android.graphics.Color.WHITE)
            }
            speakerArea.addView(speakerStripIcon)
            speakerStripName = android.widget.TextView(context).apply {
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = ""
            }
            speakerArea.addView(speakerStripName)
            // Speaker count badge (orange circle)
            val badgeSize = if (isLargeScreen) dp(26) else dp(20)
            speakerStripBadge = android.widget.TextView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                    marginStart = dp(6)
                }
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (isLargeScreen) 13f else 11f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MusicPlayerStyles.ACCENT_COLOR)
                }
                visibility = View.GONE
            }
            speakerArea.addView(speakerStripBadge)
            strip.addView(speakerArea)

            val stripParent = if (isSplitView) splitPlayerWrapper ?: parent else parent
            stripParent.addView(strip)

            // Apply active profile if cached
            updateMediaProfileStrip()
        }

        val hideWindowButtons = isOnPanel || isSplitView

        // Hide all window buttons (close, minimize, normalize) when on screensaver panel
        val btnSize = dp(54)
        val btnPad = dp(12)

        if (!isOnPanel) {
            closeButton = ImageButton(context).apply {
                layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                    if (hideWindowButtons) {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(12); rightMargin = dp(12)
                    } else {
                        gravity = Gravity.TOP or Gravity.START
                        topMargin = dp(12); leftMargin = dp(12)
                    }
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0x80808080.toInt())
                }
                setColorFilter(0xFFFFFFFF.toInt())
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(btnPad, btnPad, btnPad, btnPad)
                isClickable = true; isFocusable = true
                elevation = dp(10).toFloat()
                setOnClickListener { showCloseModal() }
            }
            parent.addView(closeButton)
        }

        if (!hideWindowButtons) {
            val topRightRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(12); rightMargin = dp(12)
                }
                elevation = dp(10).toFloat()
            }

            minimizeButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0x80808080.toInt())
                }
                setColorFilter(0xFFFFFFFF.toInt())
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_minimize)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(btnPad, btnPad, btnPad, btnPad)
                isClickable = true; isFocusable = true
                setOnClickListener { callbacks.onMinimize() }
            }
            topRightRow.addView(minimizeButton)

            normalButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0x80808080.toInt())
                }
                setColorFilter(0xFFFFFFFF.toInt())
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_compress)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(btnPad, btnPad, btnPad, btnPad)
                isClickable = true; isFocusable = true
                setOnClickListener { callbacks.onStrip() }
            }
            topRightRow.addView(normalButton)
            parent.addView(topRightRow)
        }

    }

    private fun showCloseModal() {
        val isPlaying = lastData?.isPlaying == true
        if (!isPlaying) {
            callbacks.onStop()
            return
        }
        val activity = context as? android.app.Activity ?: return
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Music Player"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text = "Would you like to:"
        val negBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative)
        val posBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive)
        val buttonsRow = negBtn.parent as? android.widget.LinearLayout
        val dialog = android.app.AlertDialog.Builder(activity).setView(dialogView).setCancelable(true).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }
        buttonsRow?.orientation = android.widget.LinearLayout.VERTICAL
        posBtn.apply {
            text = "Close Player & Stop Music"
            (layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f; marginStart = 0; bottomMargin = dp(8)
            }
            setOnClickListener { dialog.dismiss(); callbacks.onStop() }
        }
        buttonsRow?.removeView(posBtn)
        buttonsRow?.addView(posBtn, 0)
        negBtn.apply {
            text = "Hide Player & Keep Playing"
            (layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f; marginEnd = 0; bottomMargin = dp(8)
            }
            setOnClickListener { dialog.dismiss(); callbacks.onHide() }
        }
        val cancelBtn = android.widget.Button(activity).apply {
            text = "Cancel"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            isAllCaps = false
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dialog.dismiss() }
        }
        buttonsRow?.addView(cancelBtn)
        dialog.show()
    }

    private fun toggleSplitPanel(panelType: SplitPanelType) {
        if (isSplitView && splitPanelType == panelType) {
            isSplitView = false
            splitPanelType = SplitPanelType.NONE
        } else {
            isSplitView = true
            splitPanelType = panelType
        }
        android.util.Log.i(TAG, "toggleSplitPanel: type=$splitPanelType isSplitView=$isSplitView")
        val parent = currentParent ?: return
        destroy()
        parent.removeAllViews()
        render(parent)
        lastData?.let { updateViews(it) }
        if (cachedRecentlyPlayed.isNotEmpty()) {
            explorerPanel?.update(cachedRecentlyPlayed)
        }
        if (cachedSpeakers.isNotEmpty()) {
            speakerDrawer?.update(cachedSpeakers, cachedGroups)
            if (cachedCurrentTargetId.isNotEmpty()) speakerDrawer?.setCurrentlyPlaying(cachedCurrentTargetId)
        }
        // Re-apply cached profile to newly created controls
        updateMediaProfileStrip()
        // Trigger speaker polling when speaker drawer opens/closes
        if (panelType == SplitPanelType.SPEAKER_DRAWER) {
            callbacks.onSpeakerDrawerToggle?.invoke()
        }
        onExplorerToggled?.invoke()
    }

    fun updateViews(data: MusicPlayerData) {
        lastData = data
        playerControls?.updateViews(data)
        splitNormalRenderer?.updateViews(data)
        speakerStripName?.text = data.friendlyName.ifBlank {
            data.entityId.removePrefix("media_player.").ifBlank { "" }
        }
    }

    fun updateRecentlyPlayed(items: List<RecentlyPlayedData>) {
        cachedRecentlyPlayed = items
        val first = items.firstOrNull()
        android.util.Log.i(TAG, "updateRecentlyPlayed: ${items.size} groups, first has ${first?.items?.size ?: 0} items, " +
            "${first?.sections?.size ?: 0} sections, explorerPanel=${explorerPanel != null}")
        explorerPanel?.update(items)
    }

    private var cachedDataSource: MediaBrowserDataSource? = null
    fun setMediaBrowserDataSource(ds: MediaBrowserDataSource) {
        cachedDataSource = ds
        explorerPanel?.setDataSource(ds)
    }

    fun setMusicProfile(profile: MusicProfile?) {
        cachedMusicProfile = profile
        explorerPanel?.setActiveProfile(profile)
        updateMediaProfileStrip()
    }

    private fun updateMediaProfileStrip() {
        val profile = cachedMusicProfile ?: return
        val iconRes = MusicProviderIcons.iconRes(profile.providerLabel)
            .takeIf { it != 0 } ?: com.dashieapp.Dashie.R.drawable.ic_type_playlist
        mediaProfileIcon?.setImageResource(iconRes)
        mediaProfileIcon?.clearColorFilter()
        mediaProfileText?.text = "${profile.memberName}'s Music"
        // Also update the player controls bottom-left buttons
        playerControls?.updateMediaProfile(profile)
    }

    fun updateSpeakerDrawer(
        speakers: List<SpeakerGroupDrawer.SpeakerInfo>,
        groups: List<SpeakerGroupDrawer.GroupInfo> = emptyList(),
        thisDeviceId: String = "",
        currentTargetId: String = "",
        defaultEntityId: String = ""
    ) {
        cachedSpeakers = speakers
        cachedGroups = groups
        if (thisDeviceId.isNotEmpty()) cachedThisDeviceId = thisDeviceId
        if (currentTargetId.isNotEmpty()) cachedCurrentTargetId = currentTargetId
        if (defaultEntityId.isNotEmpty()) cachedDefaultEntityId = defaultEntityId
        speakerDrawer?.let { drawer ->
            if (thisDeviceId.isNotEmpty()) drawer.thisDevicePlayerId = thisDeviceId
            if (defaultEntityId.isNotEmpty()) drawer.defaultEntityId = defaultEntityId
            if (currentTargetId.isNotEmpty()) drawer.setCurrentlyPlaying(currentTargetId)
            drawer.update(speakers, groups)
        }

        // Update group volume toggle and speaker indicator
        val coord = volumeCoordinator
        val isGroup = coord?.isCurrentTargetGroup
            ?: (groups.any { it.groupId == currentTargetId } || speakers.any { it.syncedTo == currentTargetId })
        val count = coord?.speakerCount
            ?: (groups.find { it.groupId == currentTargetId }?.memberIds?.size ?: 0)
        playerControls?.setGroupActive(isGroup, count)
        updateSpeakerStripIndicator()
    }

    fun updateSpeakerDrawerCurrentTarget(targetPlayerId: String) {
        cachedCurrentTargetId = targetPlayerId
        speakerDrawer?.setCurrentlyPlaying(targetPlayerId)
        speakerDrawer?.selectTarget(targetPlayerId)
        updateSpeakerStripIndicator()
    }

    /** Update the bottom strip's speaker icon (group vs single) and count badge. */
    private fun updateSpeakerStripIndicator() {
        // Read from coordinator (single source of truth), fall back to drawer
        val coord = volumeCoordinator
        val isGroup = coord?.isCurrentTargetGroup ?: (speakerDrawer?.isCurrentTargetGroup() == true)
        val count = if (coord != null) coord.speakerCount else (speakerDrawer?.getSpeakerCount() ?: 0)
        android.util.Log.i(TAG, "🔊 updateSpeakerStripIndicator: isGroup=$isGroup count=$count " +
            "coord=${coord != null} coordGroup=${coord?.isCurrentTargetGroup} coordCount=${coord?.speakerCount} " +
            "icon=${speakerStripIcon != null} badge=${speakerStripBadge != null}")
        speakerStripIcon?.setImageResource(
            if (isGroup) com.dashieapp.Dashie.R.drawable.ic_speaker_group
            else com.dashieapp.Dashie.R.drawable.ic_box_speaker
        )
        if (count > 1) {
            speakerStripBadge?.text = "$count"
            speakerStripBadge?.visibility = View.VISIBLE
        } else {
            speakerStripBadge?.visibility = View.GONE
        }
    }

    fun updateSpeakerVolume(playerId: String, volumePercent: Int) {
        speakerDrawer?.updateSpeakerVolume(playerId, volumePercent)
    }

    fun getAlbumArtView(): ImageView? =
        splitNormalRenderer?.getAlbumArtView() ?: playerControls?.getAlbumArtView()

    fun getClickableButtons(): List<ImageButton> {
        val controls = playerControls?.getClickableButtons()
            ?: splitNormalRenderer?.getClickableButtons()
            ?: emptyList()
        val overlay = listOfNotNull(closeButton, minimizeButton, normalButton)
        return controls + overlay
    }

    /** Refresh the active profile display in the explorer panel sidebar */
    fun refreshActiveProfile() {
        explorerPanel?.setActiveProfile(musicProfileManager?.getActiveProfile())
    }

    fun destroy() {
        playerControls?.destroy()
        playerControls = null
        splitNormalRenderer?.destroy()
        splitNormalRenderer = null
        explorerPanel = null
        speakerDrawer = null
        splitPlayerWrapper = null
        speakerStripName = null
    }
}

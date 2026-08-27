package com.dashieapp.Dashie.halite.music

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.widget.SeekBar
import android.widget.TextView

/**
 * Renders the music player strip — a full-width bottom bar.
 *
 * Vertical structure:
 *   [Top gray bar: X | speaker icon + name | hide + minimize + maximize]
 *   [Recently played strip (hidden, expands between speaker bar and content)]
 *   [Main content row: AlbumArt | Title/Artist | Transport | Vertical Volume | Media selector + Speaker btn]
 */
class MusicPlayerStripRenderer(
    private val context: android.content.Context,
    private val callbacks: MusicPlayerCallbacks,
    private val onUserAction: () -> Unit
) {

    companion object {
        private const val TAG = "MusicStripRenderer"
        private const val TOP_BAR_HEIGHT_DP = 38
        private const val MIN_DRAWER_HEIGHT_DP = 150
        private const val MAX_DRAWER_HEIGHT_DP = 600
        private const val PREFS_NAME = "dashie_music_strip"
        private const val KEY_DRAWER_HEIGHT = "drawer_height_dp"
        /** Screen width (dp) below which we collapse the strip to a narrow layout:
         *  hide right-side media selector + speaker buttons, swap the profile icon
         *  in the top bar to the playlist/media-selector icon, and center the
         *  transport controls. Fire tablet portrait is ~601dp; Echo Show 5 is
         *  ~788dp — 750 keeps the Show 5 on the full layout. */
        private const val NARROW_MODE_WIDTH_DP = 750
    }

    private val isNarrowMode: Boolean
        get() {
            val metrics = context.resources.displayMetrics
            return (metrics.widthPixels / metrics.density).toInt() < NARROW_MODE_WIDTH_DP
        }

    // Scroll animation references (accessed by MusicPlayerCard)
    var trackScrollView: HorizontalScrollView? = null
        private set
    var artistScrollView: HorizontalScrollView? = null
        private set
    var trackText: TextView? = null
        private set
    var artistText: TextView? = null
        private set
    var scrollAnimatorTrack: ValueAnimator? = null
    var scrollAnimatorArtist: ValueAnimator? = null

    private var albumArtView: ImageView? = null
    private var playPauseButton: ImageButton? = null
    private var playPauseSpinner: View? = null
    private var prevButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var shuffleButton: ImageButton? = null
    private var repeatButton: ImageButton? = null
    private var progressBar: SeekBar? = null
    private var isUserScrubbing: Boolean = false
    private var positionText: TextView? = null
    private var durationText: TextView? = null
    private var speakerName: TextView? = null
    private var closeButton: ImageButton? = null
    private var minimizeButton: ImageButton? = null
    private var maximizeButton: ImageButton? = null
    private var mediaSelectorButton: ImageButton? = null
    private var speakerButton: ImageButton? = null
    private var speakerBarIcon: ImageView? = null
    private var speakerCountBadge: TextView? = null
    // Profile indicator on speaker bar
    private var profileSection: LinearLayout? = null
    private var profileIconView: ImageView? = null
    private var profileNameText: TextView? = null
    private var profileProviderText: TextView? = null  // Fallback when no icon

    // Volume — vertical stack built manually (speaker icon, +, value, -)
    private var volumeSpeakerIcon: ImageView? = null
    private var volumePlusBtn: ImageButton? = null
    private var volumeMinusBtn: ImageButton? = null
    private var volumeValueText: TextView? = null
    private var currentVolumeLevel: Float = 0f
    private var lastLocalVolumeChangeAt: Long = 0
    private var isVolumeMuted: Boolean = false
    private var isVolumeUnavailable: Boolean = false
    private var preMuteLevel: Float = 0.5f

    // Volume mode toggle (Group vs individual speaker) — reads from coordinator
    private var volumeToggleButton: TextView? = null
    /** Coordinator reference for volume state. Set from updateSpeakerDrawer. */
    var volumeCoordinator: MusicStateCoordinator? = null
    private val hasActiveGroup get() = volumeCoordinator?.isGroupActive == true
    private val isGroupVolumeMode get() = volumeCoordinator?.isGroupVolumeMode == true

    // Media browser panel (replaces recently played strip)
    private var mediaBrowserPanel: MediaBrowserPanel? = null
    private var mediaBrowserVisible = false
    private var hasRecentlyPlayed = false

    // Speaker group drawer (shares slot with recently played)
    internal var speakerDrawer: SpeakerGroupDrawer? = null
    var speakerDrawerVisible = false
        private set

    /** Override speaker bar text (set by drawer when group/transfer is active). */
    private var speakerBarOverride: String? = null

    /** Callback when strip height changes (recently played toggle). */
    var onHeightChanged: ((Int) -> Unit)? = null

    /** First recently played item, shown in idle state as the default suggestion. */
    var idleSuggestion: RecentlyPlayedItem? = null
    private var isCurrentlyIdle = false
    private var isCurrentlyPlaying = false

    /** Music profile manager for per-person music scoping */
    var musicProfileManager: MusicProfileManager? = null
    var onProfileSwitchRequested: (() -> Unit)? = null

    private val isDark get() = MusicPlayerStyles.isDarkMode(context)
    private fun dp(dp: Int) = MusicPlayerStyles.dpToPx(context, dp)
    private val stripPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /** Custom drawer height in dp, persisted across sessions.
     *  Capped so total card (top bar + strip + drawer) never exceeds screen height. */
    private var customDrawerHeightDp: Int = run {
        val saved = stripPrefs.getInt(KEY_DRAWER_HEIGHT, SpeakerGroupDrawer.DRAWER_HEIGHT_DP)
        val screenHeightDp = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density).toInt()
        val cardOverheadDp = MusicPlayerStyles.STRIP_HEIGHT_DP + TOP_BAR_HEIGHT_DP
        saved.coerceAtMost(screenHeightDp - cardOverheadDp)
    }

    // Top bar drag state
    private var dragStartRawY = 0f
    private var dragStartDrawerHeight = 0
    private var isDraggingTopBar = false

    /** Current height in dp (base + drawer/browser if visible). */
    val heightDp: Int
        get() {
            val base = MusicPlayerStyles.STRIP_HEIGHT_DP + TOP_BAR_HEIGHT_DP
            val extraHeight = when {
                speakerDrawerVisible -> customDrawerHeightDp
                mediaBrowserVisible && (hasRecentlyPlayed || mediaBrowserPanel?.hasDataSource() == true) -> customDrawerHeightDp
                else -> 0
            }
            return base + extraHeight
        }

    fun render(parent: ViewGroup) {
        val cornerR = dp(MusicPlayerStyles.CORNER_RADIUS_DP * 2).toFloat()
        val outerWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Fill background behind header + content to prevent transparent gaps
            background = GradientDrawable().apply {
                setColor(if (isDark) MusicPlayerStyles.BG_COLOR_DARK else MusicPlayerStyles.BG_COLOR_LIGHT)
                cornerRadii = floatArrayOf(cornerR, cornerR, cornerR, cornerR, 0f, 0f, 0f, 0f)
            }
            // Shadow for 3D depth — top corners rounded, bottom square
            elevation = dp(12).toFloat()
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    // Extend height beyond view to push bottom rounding off-screen
                    outline.setRoundRect(0, 0, view.width, view.height + cornerR.toInt(), cornerR)
                }
            }
            // In dark mode, use light-colored shadow for visible depth on dark backgrounds
            if (isDark && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = 0x40FFFFFF.toInt()
                outlineSpotShadowColor = 0x50FFFFFF.toInt()
            }
        }

        // ── Top gray bar (same style as normal player's bottom speaker row) ──
        buildTopBar(outerWrapper)

        // ── Media browser panel (hidden by default, between speaker bar and content) ──
        mediaBrowserPanel = MediaBrowserPanel(
            context = context,
            onItemTapped = { item ->
                Log.i(TAG, "Browser callback: playing ${item.name} uri=${item.uri}")
                callbacks.onPlayRecentItem?.invoke(item.uri)
            },
            onHeightChanged = { onHeightChanged?.invoke(heightDp) },
            onProfileTapped = { onProfileSwitchRequested?.invoke() }
        )
        // Set music profile manager and active profile (loads in background if needed)
        val mgr = musicProfileManager
        mediaBrowserPanel!!.musicProfileManager = mgr
        mediaBrowserPanel!!.onProfileSwitched = { profile -> updateSpeakerBarProfile(profile) }
        val cachedProfile = mgr?.getActiveProfile()
        mediaBrowserPanel!!.setActiveProfile(cachedProfile)
        updateSpeakerBarProfile(cachedProfile)
        if (cachedProfile == null && mgr != null) {
            Thread {
                mgr.ensureDataLoaded()
                val profile = mgr.getActiveProfile()
                if (profile != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        mediaBrowserPanel?.setActiveProfile(profile)
                        updateSpeakerBarProfile(profile)
                    }
                }
            }.start()
        }
        mediaBrowserPanel!!.view.apply {
            visibility = View.GONE
            setBackgroundColor(if (isDark) MusicPlayerStyles.BG_COLOR_DARK else MusicPlayerStyles.BG_COLOR_LIGHT)
        }
        outerWrapper.addView(mediaBrowserPanel!!.view)

        // ── Speaker group drawer (hidden by default, shares slot with recently played) ──
        speakerDrawer = SpeakerGroupDrawer(
            context = context,
            onClose = { toggleSpeakerDrawer() },
            onSpeakerToggle = { playerId, join -> callbacks.onSpeakerJoin?.invoke(playerId, join) },
            onSpeakerVolumeChange = { playerId, pct -> callbacks.onSpeakerVolumeChange?.invoke(playerId, pct) },
            onSpeakerMuteToggle = { playerId, muted -> callbacks.onSpeakerMuteToggle?.invoke(playerId, muted) },
            onSpeakerBarTextChanged = { text ->
                speakerBarOverride = text; speakerName?.text = text
                updateSpeakerCountBadge()
            },
            onTransferQueue = { id, name -> callbacks.onTransferQueue?.invoke(id, name) },
            onClearQueue = { id -> callbacks.onClearQueue?.invoke(id) },
            onGroupVolumeChange = { pct -> callbacks.onGroupVolumeChange?.invoke(pct) },
            onGroupMuteToggle = { muted -> callbacks.onGroupMuteToggle?.invoke(muted) }
        )
        // Restore persisted speaker bar text (e.g. "Dashie Tablets Group (3)")
        speakerDrawer!!.getPersistedBarText().takeIf { it.isNotEmpty() }?.let {
            speakerBarOverride = it
            speakerName?.text = it
        }
        // Restore persisted group icon
        if (speakerDrawer!!.getPersistedIsGroup()) {
            speakerBarIcon?.setImageResource(com.dashieapp.Dashie.R.drawable.ic_speaker_group)
        }
        // Restore persisted speaker count badge
        val persistedCount = speakerDrawer!!.getPersistedSpeakerCount()
        if (persistedCount > 1) {
            speakerCountBadge?.text = "$persistedCount"
            speakerCountBadge?.visibility = View.VISIBLE
        }
        outerWrapper.addView(speakerDrawer!!.view)

        // ── Main content row ──
        buildContentRow(outerWrapper)

        parent.addView(outerWrapper)
    }

    private fun buildTopBar(outerWrapper: LinearLayout) {
        val barHeight = dp(TOP_BAR_HEIGHT_DP)
        val renderer = this

        val topBar = object : LinearLayout(context) {
            private var interceptStartY = 0f
            private var intercepting = false

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (!renderer.speakerDrawerVisible && !renderer.mediaBrowserVisible) return false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        interceptStartY = ev.rawY
                        intercepting = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = Math.abs(ev.rawY - interceptStartY)
                        if (dy > dp(5)) {
                            intercepting = true
                            return true  // Steal the touch from children
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!renderer.speakerDrawerVisible && !renderer.mediaBrowserVisible) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        renderer.dragStartRawY = event.rawY
                        renderer.dragStartDrawerHeight = renderer.customDrawerHeightDp
                        renderer.isDraggingTopBar = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = renderer.dragStartRawY - event.rawY
                        val deltaDp = (deltaY / context.resources.displayMetrics.density).toInt()
                        if (!renderer.isDraggingTopBar && Math.abs(deltaDp) > 3) {
                            renderer.isDraggingTopBar = true
                        }
                        if (renderer.isDraggingTopBar) {
                            val screenDp = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density).toInt()
                            val maxDp = (screenDp - MusicPlayerStyles.STRIP_HEIGHT_DP).coerceAtMost(MAX_DRAWER_HEIGHT_DP)
                            renderer.customDrawerHeightDp = (renderer.dragStartDrawerHeight + deltaDp)
                                .coerceIn(MIN_DRAWER_HEIGHT_DP, maxDp)
                            renderer.applyDrawerHeight()
                            renderer.onHeightChanged?.invoke(renderer.heightDp)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (renderer.isDraggingTopBar) {
                            renderer.stripPrefs.edit().putInt(KEY_DRAWER_HEIGHT, renderer.customDrawerHeightDp).apply()
                            Log.i(TAG, "Drawer height saved: ${renderer.customDrawerHeightDp}dp")
                            renderer.isDraggingTopBar = false
                            return true
                        }
                        renderer.isDraggingTopBar = false
                        return false
                    }
                }
                return false
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barHeight
            )
            setPadding(dp(8), 0, dp(8), 0)
            // Dark mode: distinct dark gray; Light mode: semi-transparent dark overlay
            // Outer wrapper clips to rounded rect, so no corner radii needed here
            setBackgroundColor(if (isDark) MusicPlayerStyles.HEADER_BG_DARK else 0x80000000.toInt())
        }

        // X (close) button — left side — shows modal: Hide / Close & Stop
        closeButton = ImageButton(context).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true; isFocusable = true
            setOnClickListener { showCloseModal() }
        }
        topBar.addView(closeButton)

        // Center section: [Profile] · [Speaker]  (fills remaining space)
        val centerSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Profile section (tappable — opens media browser with profile selector)
        profileSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnClickListener { toggleMediaBrowserWithProfile() }
            visibility = View.GONE  // Hidden until profile is loaded
        }
        profileIconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        profileSection!!.addView(profileIconView)
        profileNameText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 14f))
            maxLines = 1
        }
        profileSection!!.addView(profileNameText)
        // Fallback provider text (shown when no icon available)
        profileProviderText = TextView(context).apply {
            setTextColor(0xAAFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 12f))
            maxLines = 1
            visibility = View.GONE
        }
        profileSection!!.addView(profileProviderText)
        centerSection.addView(profileSection)

        // Dot separator (between profile and speaker)
        val dotSeparator = TextView(context).apply {
            text = "  ·  "
            setTextColor(0x80FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 20f))
            tag = "profile_dot"
            visibility = View.GONE
        }
        centerSection.addView(dotSeparator)

        // Speaker section (tappable — opens speaker drawer)
        val speakerSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnClickListener { toggleSpeakerDrawer() }
        }
        speakerBarIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_box_speaker)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            speakerSection.addView(this)
        }
        speakerName = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 15f))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = ""
        }
        speakerSection.addView(speakerName)

        // Speaker count badge (filled circle with number)
        speakerCountBadge = TextView(context).apply {
            val size = dp(20)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) }
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 11f))
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
            visibility = View.GONE
        }
        speakerSection.addView(speakerCountBadge)
        centerSection.addView(speakerSection)

        topBar.addView(centerSection)

        // Maximize (expand) button — where hide was
        maximizeButton = ImageButton(context).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setColorFilter(Color.WHITE)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_expand)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true; isFocusable = true
            setOnClickListener { callbacks.onMaximize() }
        }
        topBar.addView(maximizeButton)

        // Minimize (down chevron) button — rightmost
        minimizeButton = ImageButton(context).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40FFFFFF.toInt())
            }
            setColorFilter(Color.WHITE)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_down)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (speakerDrawerVisible) {
                    toggleSpeakerDrawer()  // Close speaker drawer first
                } else if (mediaBrowserVisible) {
                    toggleMediaBrowser()  // Close media browser first
                } else {
                    callbacks.onMinimize()
                }
            }
        }
        topBar.addView(minimizeButton)

        outerWrapper.addView(topBar)
    }

    private fun buildContentRow(outerWrapper: LinearLayout) {
        val artSize = dp((MusicPlayerStyles.STRIP_ART_SIZE_DP * 0.9f).toInt())  // 10% smaller
        // Scale gaps based on screen width — smaller screens get tighter spacing
        val screenWidthDp = (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
        val gapDp = if (screenWidthDp < 800) 20 else 50
        val groupGap = dp(gapDp)
        val narrow = isNarrowMode
        // On smaller full-layout screens (e.g. Echo Show 5, ~788dp) the strip
        // reserves the sidebar on its left and stretches to fill the width, so the
        // fixed transport/volume/selector controls leave little room — shrink the
        // title/artist scroll area and (in the assembly below) right-align the
        // selector buttons so nothing clips. Wider screens keep the strip compact
        // and centered with the full 160dp title. STRIP_COMPACT_MIN_WIDTH_DP must
        // match the card-layout branch in MusicPlayerCard.
        val stretchToFill = !narrow &&
            screenWidthDp < MusicPlayerStyles.STRIP_COMPACT_MIN_WIDTH_DP
        val titleScrollWidth = dp(
            when {
                narrow -> 110
                stretchToFill -> 96
                else -> 160
            }
        )

        val contentBg = GradientDrawable().apply {
            setColor(if (isDark) MusicPlayerStyles.BG_COLOR_DARK else MusicPlayerStyles.BG_COLOR_LIGHT)
            setStroke(dp(1), if (isDark) MusicPlayerStyles.BORDER_COLOR_DARK
                else MusicPlayerStyles.BORDER_COLOR_LIGHT)
        }

        // ── Album art + text column (used by both layouts) ──
        albumArtView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                marginEnd = dp(8)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        trackScrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(titleScrollWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        trackText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textPrimary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 16f))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        trackScrollView?.addView(trackText)
        textColumn.addView(trackScrollView)

        artistScrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(titleScrollWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        artistText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textSecondary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 14f))
            maxLines = 1
        }
        artistScrollView?.addView(artistText)
        textColumn.addView(artistScrollView)

        // ── Group 1: Album art + text column ──
        val group1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (!narrow) marginEnd = dp(25) }
        }
        group1.addView(albumArtView)
        group1.addView(textColumn)

        // ── Transport row: prev / play-pause / next ──
        val transportRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (!narrow) bottomMargin = dp(4) }
        }
        if (!narrow) {
            shuffleButton = createToggleButton(com.dashieapp.Dashie.R.drawable.ic_shuffle, 30) {
                callbacks.onShuffleToggle?.invoke()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6) }
            transportRow.addView(shuffleButton)
        }

        prevButton = createControlButton(com.dashieapp.Dashie.R.drawable.ic_previous, 40) {
            callbacks.onPrevious()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4) }
        transportRow.addView(prevButton)

        val playPauseSize = dp(43)
        val playPauseWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(playPauseSize, playPauseSize).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
        }
        playPauseButton = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(playPauseSize, playPauseSize)
            background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener {
                if (isCurrentlyIdle && idleSuggestion != null) {
                    Log.i(TAG, "Idle suggestion play: ${idleSuggestion!!.name}")
                    callbacks.onPlayRecentItem?.invoke(idleSuggestion!!.uri)
                } else {
                    onUserAction()
                    callbacks.onPlayPause()
                }
            }
        }
        playPauseWrapper.addView(playPauseButton)
        playPauseSpinner = MusicPlayerStyles.createReconnectingSpinner(context, playPauseSize)
        playPauseWrapper.addView(playPauseSpinner)
        transportRow.addView(playPauseWrapper)

        nextButton = createControlButton(com.dashieapp.Dashie.R.drawable.ic_next, 40) {
            callbacks.onNext()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) }
        transportRow.addView(nextButton)

        if (!narrow) {
            repeatButton = createToggleButton(com.dashieapp.Dashie.R.drawable.ic_repeat, 30) {
                callbacks.onRepeatCycle?.invoke()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) }
            transportRow.addView(repeatButton)
        }

        // ── Progress row ──
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        positionText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textSecondary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 12f)); text = "0:00"
        }
        progressRow.addView(positionText)
        progressBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (narrow) dp(80) else dp(120),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
            max = 1; progress = 0
            progressDrawable = MusicPlayerStyles.createProgressDrawable(context)
            // setMaxHeight/setMinHeight on the View hierarchy were added in
            // API 29 — older Fire OS / Android 9 devices crash on these
            // calls when R8 resolves them against View instead of ProgressBar.
            // See MusicPlayerNormalRenderer for the same guard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                maxHeight = dp(3)
                minHeight = dp(3)
            }
            thumb = MusicPlayerStyles.createSeekThumb(context, sizeDp = 11)
            val vPad = dp(6)
            val hPad = dp(6)
            setPadding(hPad, vPad, hPad, vPad)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        positionText?.text = "%d:%02d".format(progress / 60, progress % 60)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    isUserScrubbing = true
                    onUserAction()
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    isUserScrubbing = false
                    callbacks.onSeek?.invoke((bar?.progress ?: 0) * 1000L)
                }
            })
        }
        progressRow.addView(progressBar)
        durationText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textSecondary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 12f)); text = "0:00"
        }
        progressRow.addView(durationText)

        // In wide mode the progress row stacks under the transport row inside
        // group 2. In narrow mode we keep it as a loose view and dock it at
        // the bottom-center of the FrameLayout below, horizontally aligned
        // under the centered play button.

        // ── Group 3: Volume controls + Group/Speaker toggle ──
        val volumeWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (!narrow) marginEnd = groupGap }
        }
        val group3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        buildHorizontalVolume(group3)
        volumeWrapper.addView(group3)

        volumeToggleButton = TextView(context).apply {
            text = "Group"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 12f))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val h = dp(26)
            minWidth = dp(92)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, h
            ).apply { topMargin = dp(2) }
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
            alpha = 0.85f
            visibility = View.GONE
            isClickable = true; isFocusable = true
            setOnClickListener { toggleVolumeMode() }
        }
        volumeWrapper.addView(volumeToggleButton)

        // ── Group 4: Media selector + Speaker buttons (wide mode only) ──
        val group4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (narrow) visibility = View.GONE
        }
        mediaSelectorButton = createRightButton(
            com.dashieapp.Dashie.R.drawable.ic_type_playlist
        ) { toggleMediaBrowser() }
        group4.addView(mediaSelectorButton)
        speakerButton = createRightButton(
            com.dashieapp.Dashie.R.drawable.ic_box_speaker
        ) { toggleSpeakerDrawer() }
        group4.addView(speakerButton)

        // ── Assemble ──
        if (narrow) {
            // FrameLayout gives play/pause absolute horizontal + vertical centering
            // on the strip regardless of left (album+title) or right (volume) widths.
            val container = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                setPadding(dp(MusicPlayerStyles.PADDING_DP), dp(1),
                    dp(MusicPlayerStyles.PADDING_DP), dp(1))
                background = contentBg
            }
            container.addView(group1, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            // Transport + progress are stacked in a centered vertical group so
            // the play button isn't overlapped by the SeekBar thumb.
            val transportGroup = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            transportGroup.addView(transportRow)
            transportGroup.addView(progressRow)
            container.addView(transportGroup, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
            container.addView(volumeWrapper, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
            outerWrapper.addView(container)
        } else {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Stretch mode (small screens): MATCH_PARENT so the content
                // background fills the full strip width beside the sidebar, with a
                // trailing spacer (added below) pushing the selector buttons flush
                // to the right edge. Compact mode (wide screens): WRAP_CONTENT so
                // the bar sizes to its controls and the card centers it on screen,
                // instead of stretching the controls to the corners.
                layoutParams = LinearLayout.LayoutParams(
                    if (stretchToFill) LinearLayout.LayoutParams.MATCH_PARENT
                        else LinearLayout.LayoutParams.WRAP_CONTENT,
                    0, 1f
                )
                setPadding(dp(MusicPlayerStyles.PADDING_DP), dp(1),
                    dp(MusicPlayerStyles.PADDING_DP), dp(1))
                background = contentBg
            }
            val group2 = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = groupGap }
            }
            group2.addView(transportRow)
            group2.addView(progressRow)

            container.addView(group1)
            container.addView(group2)
            container.addView(volumeWrapper)
            if (stretchToFill) {
                // Absorbs leftover width so group4 (media-selector + speaker) sits
                // flush against the right edge and the box fills the strip.
                val rightSpacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                }
                container.addView(rightSpacer)
            }
            container.addView(group4)
            outerWrapper.addView(container)
        }
    }

    private fun buildHorizontalVolume(parent: LinearLayout) {
        // Horizontal: [speaker icon] [-] [value] [+]
        val btnSize = dp(34)   // 20% larger
        val speakerIconSize = dp(34)
        val grayedOut = (MusicPlayerStyles.textSecondary(context) and 0x00FFFFFF) or 0x60000000

        volumeSpeakerIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(speakerIconSize, speakerIconSize).apply {
                marginEnd = dp(4)
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_volume_up)
            imageTintList = android.content.res.ColorStateList.valueOf(grayedOut)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { toggleMute() }
        }
        parent.addView(volumeSpeakerIcon)

        volumeMinusBtn = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_remove)
            setColorFilter(grayedOut)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true; isFocusable = true
            setOnClickListener { adjustVolume(-0.1f) }
        }
        parent.addView(volumeMinusBtn)

        volumeValueText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setTextColor(grayedOut)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MusicPlayerStyles.scaledSp(context, 18f))  // 20% larger
            minWidth = dp(24)
            text = "-"
        }
        parent.addView(volumeValueText)

        volumePlusBtn = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_add)
            setColorFilter(grayedOut)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true; isFocusable = true
            setOnClickListener { adjustVolume(0.1f) }
        }
        parent.addView(volumePlusBtn)
    }

    /** Creates a round icon button for the right section (media selector, speaker, maximize). */
    private fun createRightButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            val size = dp(46)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) MusicPlayerStyles.CLOSE_BG_DARK else MusicPlayerStyles.CLOSE_BG_LIGHT)
            }
            setImageResource(iconRes)
            setColorFilter(if (isDark) MusicPlayerStyles.CLOSE_ICON_DARK else MusicPlayerStyles.CLOSE_ICON_LIGHT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ── Volume mode toggle ──

    /** Update toggle visibility from coordinator state. */
    fun refreshVolumeToggle() {
        if (hasActiveGroup) {
            volumeToggleButton?.visibility = View.VISIBLE
            volumeToggleButton?.text = if (isGroupVolumeMode) "Group" else {
                volumeCoordinator?.thisDeviceSpeakerName?.takeIf { it.isNotEmpty() }
                    ?: run {
                        val drawer = speakerDrawer
                        val thisId = drawer?.thisDevicePlayerId ?: ""
                        drawer?.allSpeakers?.find { thisId.isNotEmpty() && it.playerId.contains(thisId) }?.displayName
                    }
                    ?: android.os.Build.MODEL
            }
        } else {
            volumeToggleButton?.visibility = View.GONE
        }
    }

    private fun toggleVolumeMode() {
        val coord = volumeCoordinator ?: return
        if (!coord.isGroupActive) return
        coord.toggleVolumeMode()
        refreshVolumeToggle()
        refreshVolumeDisplay()
    }

    /** Refresh the volume display from the coordinator's current state. */
    fun refreshVolumeDisplay() {
        val coord = volumeCoordinator ?: return
        val pct = coord.getDisplayVolumePercent()
        val muted = coord.getDisplayMuted()
        val scale = if (muted) 0 else coord.volumeScale(pct)
        currentVolumeLevel = pct / 100f
        isVolumeMuted = muted
        isVolumeUnavailable = false
        applyVolumeColors(MusicPlayerStyles.textSecondary(context))
        volumeValueText?.text = if (muted) "0" else "$scale"
        updateVolumeSpeakerIcon()
        // Keep drawer's group volume tab in sync when strip changes volume
        speakerDrawer?.refreshGroupVolumeFromCoordinator()
    }

    // ── Volume control logic (mirrors MusicVolumeBar) ──

    private fun adjustVolume(delta: Float) {
        val coord = volumeCoordinator
        val iconColor = MusicPlayerStyles.textSecondary(context)
        if (isVolumeUnavailable) {
            isVolumeUnavailable = false
            currentVolumeLevel = 0.5f
            applyVolumeColors(iconColor)
        }
        val wasMuted = isVolumeMuted
        val oldPercent = (currentVolumeLevel * 100).toInt()
        val isGroup = isGroupVolumeMode && hasActiveGroup
        // Group volume floors at 10% (scale 1) — use mute button to mute
        val minLevel = if (isGroup) 0.1f else 0f
        val newLevel = (currentVolumeLevel + delta).coerceIn(minLevel, 1f)
        val newPercent = (newLevel * 100).toInt()
        currentVolumeLevel = newLevel
        isVolumeMuted = if (isGroup) false else newLevel < 0.01f

        // Update coordinator state immediately
        coord?.markLocalVolumeChange()
        if (isGroup) {
            coord?.groupVolumePercent = newPercent
            coord?.groupVolumeMuted = false
            if (wasMuted && !isVolumeMuted) callbacks.onGroupMuteToggle?.invoke(false)
            // Use per-speaker commands with floor to prevent zeroing out speakers
            val usedPerSpeaker = speakerDrawer?.applyGroupVolumeWithFloor(oldPercent, newPercent) ?: false
            if (!usedPerSpeaker) {
                callbacks.onGroupVolumeChange?.invoke(newPercent)
            }
        } else {
            coord?.individualVolumePercent = newPercent
            coord?.individualVolumeMuted = isVolumeMuted
            callbacks.onVolumeChange?.invoke(newLevel)
        }
        refreshVolumeDisplay()
    }

    private fun toggleMute() {
        val coord = volumeCoordinator ?: return
        coord.pinMuteState(!isVolumeMuted)  // Pin display mute state to prevent oscillation
        val iconColor = MusicPlayerStyles.textSecondary(context)
        if (isVolumeUnavailable) {
            isVolumeUnavailable = false
            currentVolumeLevel = 0.5f
            applyVolumeColors(iconColor)
        }
        coord.markLocalVolumeChange()
        if (isVolumeMuted) {
            // Unmute — restore per-speaker volumes if available, else fall back to group volume.
            val restorePercent = coord.getPreMutePercent()
            currentVolumeLevel = restorePercent / 100f
            isVolumeMuted = false
            if (isGroupVolumeMode && hasActiveGroup) {
                coord.groupVolumeMuted = false
                coord.groupVolumePercent = restorePercent
                speakerDrawer?.setAllSpeakersMutedDisplay(false)
                callbacks.onGroupMuteToggle?.invoke(false)
                // Restore individual speaker volumes to their pre-mute levels.
                // Falls back to single group volume if drawer hasn't been populated.
                val restoredPerSpeaker = speakerDrawer?.restorePerSpeakerVolumes() ?: false
                if (!restoredPerSpeaker) {
                    callbacks.onGroupVolumeChange?.invoke(restorePercent)
                }
            } else {
                coord.individualVolumeMuted = false
                coord.individualVolumePercent = restorePercent
                callbacks.onVolumeChange?.invoke(currentVolumeLevel)
            }
        } else {
            // Mute — save per-speaker volumes and coordinator's group pre-mute level
            coord.savePreMutePercent(coord.getDisplayVolumePercent())
            speakerDrawer?.savePerSpeakerVolumes()
            currentVolumeLevel = 0f
            isVolumeMuted = true
            if (isGroupVolumeMode && hasActiveGroup) {
                coord.groupVolumeMuted = true
                speakerDrawer?.setAllSpeakersMutedDisplay(true)
                callbacks.onGroupMuteToggle?.invoke(true)
            } else {
                coord.individualVolumeMuted = true
                callbacks.onVolumeChange?.invoke(0f)
            }
        }
        refreshVolumeDisplay()
    }

    private fun applyVolumeColors(color: Int) {
        volumeValueText?.setTextColor(color)
        volumeSpeakerIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)
        volumePlusBtn?.setColorFilter(color)
        volumeMinusBtn?.setColorFilter(color)
    }

    private fun updateVolumeSpeakerIcon() {
        volumeSpeakerIcon?.setImageResource(
            if (isVolumeMuted) com.dashieapp.Dashie.R.drawable.ic_volume_off
            else com.dashieapp.Dashie.R.drawable.ic_volume_up
        )
    }

    fun updateVolume(level: Float, muted: Boolean, unavailable: Boolean = false) {
        val coord = volumeCoordinator
        if (coord != null && coord.isVolumeDebounceActive()) return

        val grayedOut = (MusicPlayerStyles.textSecondary(context) and 0x00FFFFFF) or 0x60000000
        val iconColor = MusicPlayerStyles.textSecondary(context)
        if (unavailable) {
            isVolumeUnavailable = true
            volumeValueText?.text = "-"
            applyVolumeColors(grayedOut)
            volumeSpeakerIcon?.setImageResource(com.dashieapp.Dashie.R.drawable.ic_volume_up)
            return
        }
        if (isVolumeUnavailable) {
            isVolumeUnavailable = false
            applyVolumeColors(iconColor)
        }
        // Update coordinator's individual volume and refresh from single source
        coord?.updateIndividualVolume((level * 100).toInt(), muted)
        if (!muted && level > 0.01f) preMuteLevel = level
        refreshVolumeDisplay()
    }

    // ── Close modal ──

    private fun showCloseModal() {
        // If music isn't actively playing, just close directly — no need to prompt
        if (!isCurrentlyPlaying) {
            callbacks.onStop()
            return
        }
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

        // Stack buttons vertically: Close & Stop, Hide, Cancel
        buttonsRow?.orientation = LinearLayout.VERTICAL

        // Top button: Close & Stop (orange/primary style)
        posBtn.apply {
            text = "Close Player & Stop Music"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f; marginStart = 0; bottomMargin = dp(8)
            }
            setOnClickListener { dialog.dismiss(); callbacks.onStop() }
        }
        // Reorder: move posBtn to top
        buttonsRow?.removeView(posBtn)
        buttonsRow?.addView(posBtn, 0)

        // Middle button: Hide Player (border style)
        negBtn.apply {
            text = "Hide Player & Keep Playing"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f; marginEnd = 0; bottomMargin = dp(8)
            }
            setOnClickListener { dialog.dismiss(); callbacks.onHide() }
        }

        // Bottom button: Cancel
        val cancelBtn = android.widget.Button(activity).apply {
            text = "Cancel"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            isAllCaps = false
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dialog.dismiss() }
        }
        buttonsRow?.addView(cancelBtn)

        dialog.show()
    }

    // ── Speaker group drawer ──

    fun toggleSpeakerDrawer() {
        speakerDrawerVisible = !speakerDrawerVisible
        if (speakerDrawerVisible) {
            speakerDrawer?.view?.visibility = View.VISIBLE
            mediaBrowserPanel?.view?.visibility = View.GONE
            mediaBrowserVisible = false
            applyDrawerHeight()
        } else {
            speakerDrawer?.view?.visibility = View.GONE
        }
        updateToggleButtonColors()
        // Notify wiring (starts/stops polling)
        callbacks.onSpeakerDrawerToggle?.invoke()
        onHeightChanged?.invoke(heightDp)
    }

    fun updateSpeakerDrawer(speakers: List<SpeakerGroupDrawer.SpeakerInfo>, groups: List<SpeakerGroupDrawer.GroupInfo> = emptyList(), thisDeviceId: String = "", currentTargetId: String = "", defaultEntityId: String = "") {
        if (thisDeviceId.isNotEmpty()) speakerDrawer?.thisDevicePlayerId = thisDeviceId
        if (defaultEntityId.isNotEmpty()) speakerDrawer?.defaultEntityId = defaultEntityId
        if (currentTargetId.isNotEmpty()) speakerDrawer?.setCurrentlyPlaying(currentTargetId)
        speakerDrawer?.update(speakers, groups)
        speakerDrawer?.getFormattedSpeakerBarText()?.takeIf { it.isNotEmpty() }?.let {
            speakerBarOverride = it
            speakerName?.text = it
        }
        updateSpeakerBarIcon()
        updateSpeakerCountBadge()

        // Refresh volume toggle and display from coordinator (single source of truth)
        refreshVolumeToggle()
        val coord = volumeCoordinator
        if (coord != null && !coord.isVolumeDebounceActive()) {
            refreshVolumeDisplay()
        }
    }

    fun updateSpeakerDrawerCurrentTarget(targetPlayerId: String) {
        speakerDrawer?.setCurrentlyPlaying(targetPlayerId)
        speakerDrawer?.selectTarget(targetPlayerId)
        speakerDrawer?.getFormattedSpeakerBarText()?.takeIf { it.isNotEmpty() }?.let {
            speakerBarOverride = it
            speakerName?.text = it
        }
        updateSpeakerBarIcon()
        updateSpeakerCountBadge()
    }

    private fun updateSpeakerBarIcon() {
        val isGroup = speakerDrawer?.isCurrentTargetGroup() == true
        speakerBarIcon?.setImageResource(
            if (isGroup) com.dashieapp.Dashie.R.drawable.ic_speaker_group
            else com.dashieapp.Dashie.R.drawable.ic_box_speaker
        )
    }

    fun configureVisibilityStore(haBaseUrl: String, haAuthToken: String) {
        speakerDrawer?.visibilityStore?.apply {
            this.haBaseUrl = haBaseUrl
            this.haAuthToken = haAuthToken
            fetchGlobalFromHa()
        }
    }

    private fun updateSpeakerCountBadge() {
        val count = speakerDrawer?.getSpeakerCount() ?: 0
        if (count > 1) {
            speakerCountBadge?.text = "$count"
            speakerCountBadge?.visibility = View.VISIBLE
        } else {
            speakerCountBadge?.visibility = View.GONE
        }
    }

    fun updateSpeakerVolume(playerId: String, volumePercent: Int) {
        speakerDrawer?.updateSpeakerVolume(playerId, volumePercent)
    }


    fun getSpeakerDrawerBounds(card: android.view.View): android.graphics.Rect? {
        return speakerDrawer?.getBounds(card)
    }

    /** Apply the current custom drawer height to whichever panel is visible. */
    private fun applyDrawerHeight() {
        val heightPx = dp(customDrawerHeightDp)
        if (speakerDrawerVisible) {
            speakerDrawer?.view?.layoutParams?.height = heightPx
            speakerDrawer?.view?.requestLayout()
        }
        if (mediaBrowserVisible) {
            mediaBrowserPanel?.view?.let { v ->
                v.layoutParams?.height = heightPx
                // Also resize the expanded view inside the panel
                (v as? android.widget.FrameLayout)?.getChildAt(0)?.let { child ->
                    child.layoutParams?.height = heightPx
                    child.requestLayout()
                }
                v.requestLayout()
            }
        }
    }

    /** Update speaker + media selector button colors based on which panel is open. */
    private fun updateToggleButtonColors() {
        val defaultColor = if (isDark) MusicPlayerStyles.CLOSE_ICON_DARK else MusicPlayerStyles.CLOSE_ICON_LIGHT
        speakerButton?.setColorFilter(
            if (speakerDrawerVisible) MusicPlayerStyles.ACCENT_COLOR else defaultColor
        )
        mediaSelectorButton?.setColorFilter(
            if (mediaBrowserVisible) MusicPlayerStyles.ACCENT_COLOR else defaultColor
        )
    }

    // ── Media browser toggle ──

    private fun toggleMediaBrowser() {
        // Close speaker drawer if open (mutual exclusivity)
        if (speakerDrawerVisible) {
            speakerDrawerVisible = false
            speakerDrawer?.view?.visibility = View.GONE
            callbacks.onSpeakerDrawerToggle?.invoke()
        }
        mediaBrowserVisible = !mediaBrowserVisible
        // Show panel if we have recents OR a data source (categories can load independently)
        val hasContent = hasRecentlyPlayed || mediaBrowserPanel?.hasDataSource() == true
        if (hasContent) {
            mediaBrowserPanel?.view?.visibility =
                if (mediaBrowserVisible) View.VISIBLE else View.GONE
            // Re-apply cached data when becoming visible (grid may not have been populated
            // if data arrived while panel was GONE)
            if (mediaBrowserVisible) mediaBrowserPanel?.refreshFromCache()
        }
        if (mediaBrowserVisible) applyDrawerHeight()
        updateToggleButtonColors()
        onHeightChanged?.invoke(heightDp)
    }

    fun updateRecentlyPlayed(data: RecentlyPlayedData) {
        val hadRecents = hasRecentlyPlayed
        hasRecentlyPlayed = data.items.isNotEmpty()
        mediaBrowserPanel?.update(data)
        if (!mediaBrowserVisible) {
            mediaBrowserPanel?.view?.visibility = View.GONE
        }
        if (hadRecents != hasRecentlyPlayed) {
            onHeightChanged?.invoke(heightDp)
        }
    }

    /** Set the data source for library browsing on the media browser panel. */
    fun setMediaBrowserDataSource(ds: MediaBrowserDataSource) {
        mediaBrowserPanel?.setDataSource(ds)
    }

    fun getRecentlyPlayedBounds(card: View): Rect? {
        return mediaBrowserPanel?.getBounds(card)
    }

    // ── Top bar button helper ──

    private fun createTopBarButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            val size = dp(28)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MusicPlayerStyles.CLOSE_BG_DARK)
            }
            setColorFilter(MusicPlayerStyles.CLOSE_ICON_DARK)
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ── Public API ──

    fun updateViews(data: MusicPlayerData) {
        val isIdleConnected = !data.hasMedia && !data.isReconnecting && data.entityId.isNotEmpty()
        isCurrentlyIdle = isIdleConnected
        isCurrentlyPlaying = data.isPlaying

        if (isIdleConnected) {
            val suggestion = idleSuggestion
            if (suggestion != null) {
                when (suggestion.mediaType.lowercase()) {
                    "artist" -> {
                        trackText?.text = "(Artist) ${suggestion.name}"
                        artistText?.text = "Recently played artist"
                    }
                    "album" -> {
                        trackText?.text = "(Album) ${suggestion.name}"
                        artistText?.text = suggestion.artist ?: ""
                    }
                    "playlist" -> {
                        trackText?.text = "(Playlist) ${suggestion.name}"
                        artistText?.text = "Recently played playlist"
                    }
                    else -> {
                        trackText?.text = suggestion.name
                        artistText?.text = suggestion.artist ?: "Recently played"
                    }
                }
            } else {
                trackText?.text = "No music playing"
                artistText?.text = "Select a song from the media library"
            }
            if (!isUserScrubbing) {
                positionText?.text = "0:00"
                durationText?.text = "0:00"
                progressBar?.max = 1
                progressBar?.progress = 0
            }
            progressBar?.isEnabled = false
            playPauseButton?.setImageResource(com.dashieapp.Dashie.R.drawable.ic_play)
            playPauseButton?.setColorFilter(Color.WHITE)
            if (suggestion != null) {
                playPauseButton?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
                playPauseButton?.isClickable = true
            } else {
                playPauseButton?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
                playPauseButton?.isClickable = false
            }
            playPauseSpinner?.visibility = View.GONE
        } else {
            if (data.isReconnecting) {
                trackText?.text = "Reconnecting"
                artistText?.text = "Music Assistant"
            } else {
                trackText?.text = data.trackName.ifBlank { "Unknown Track" }
                artistText?.text = data.artistName.ifBlank { "Unknown Artist" }
            }
            if (!isUserScrubbing) {
                positionText?.text = data.formattedPosition
                durationText?.text = data.formattedDuration
                progressBar?.max = data.durationSeconds.coerceAtLeast(1)
                progressBar?.progress = data.positionSeconds
            }
            progressBar?.isEnabled = !data.isReconnecting && data.durationSeconds > 0

            if (data.isReconnecting) {
                playPauseButton?.setImageDrawable(null)
                playPauseButton?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
                playPauseButton?.isClickable = false
                playPauseSpinner?.visibility = View.VISIBLE
            } else {
                playPauseButton?.setImageResource(
                    if (data.isPlaying) com.dashieapp.Dashie.R.drawable.ic_pause
                    else com.dashieapp.Dashie.R.drawable.ic_play
                )
                playPauseButton?.setColorFilter(Color.WHITE)
                playPauseButton?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
                playPauseButton?.isClickable = true
                playPauseSpinner?.visibility = View.GONE
            }
        }

        shuffleButton?.let {
            MusicPlayerStyles.applyToggleButtonState(it, data.shuffleEnabled)
        }
        repeatButton?.let {
            it.setImageResource(
                if (data.repeatMode == "one") com.dashieapp.Dashie.R.drawable.ic_repeat_one
                else com.dashieapp.Dashie.R.drawable.ic_repeat
            )
            MusicPlayerStyles.applyToggleButtonState(it, data.repeatMode != "off")
        }

        // Volume — always refresh from coordinator (single source of truth).
        // Don't write poller's data.volumeLevel to coordinator here — that's the
        // local Android volume, not the MA volume. The coordinator gets MA volume
        // from fetchAndUpdateSpeakers (speaker poll).
        val coord = volumeCoordinator
        if (coord != null) {
            refreshVolumeDisplay()
        } else {
            updateVolume(data.volumeLevel, data.isVolumeMuted, data.isVolumeUnavailable)
        }

        // Show group toggle if entity is a sync group (even before drawer opens)
        if (!hasActiveGroup && data.entityId.startsWith("syncgroup_")) {
            val coord = volumeCoordinator
            if (coord != null) coord.isGroupActive = true
            refreshVolumeToggle()
        }

        // Speaker name: drawer override > persisted bar text > data.friendlyName
        if (speakerBarOverride == null) {
            val persisted = speakerDrawer?.getPersistedBarText()?.takeIf { it.isNotEmpty() }
            speakerName?.text = persisted ?: data.friendlyName.ifBlank {
                if (data.entityId.startsWith("media_player.")) {
                    data.entityId.removePrefix("media_player.").replace("_", " ")
                } else {
                    android.os.Build.MODEL
                }
            }
        }
    }

    fun getAlbumArtView(): ImageView? = albumArtView

    fun getClickableButtons(): List<ImageButton> = listOfNotNull(
        closeButton, playPauseButton, prevButton, nextButton,
        shuffleButton, repeatButton,
        minimizeButton, maximizeButton, mediaSelectorButton, speakerButton,
        volumePlusBtn, volumeMinusBtn
    )

    /** Refresh the active profile display in the browser panel sidebar and speaker bar */
    fun refreshActiveProfile() {
        val profile = musicProfileManager?.getActiveProfile()
        mediaBrowserPanel?.setActiveProfile(profile)
        updateSpeakerBarProfile(profile)
    }

    /** Update the profile indicator on the speaker bar */
    private fun updateSpeakerBarProfile(profile: MusicProfile?) {
        if (profile == null) {
            profileSection?.visibility = View.GONE
            profileSection?.parent?.let { parent ->
                (parent as? ViewGroup)?.findViewWithTag<View>("profile_dot")?.visibility = View.GONE
            }
            return
        }

        profileSection?.visibility = View.VISIBLE
        profileSection?.parent?.let { parent ->
            (parent as? ViewGroup)?.findViewWithTag<View>("profile_dot")?.visibility = View.VISIBLE
        }

        profileNameText?.text = profile.memberName

        // Narrow mode: override provider icon with the media-selector (playlist)
        // icon so the tap target clearly signals "open the media library" rather
        // than "that's your Spotify account". The profile name still shows.
        if (isNarrowMode) {
            profileIconView?.setImageResource(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
            profileIconView?.setColorFilter(Color.WHITE)
            profileIconView?.visibility = View.VISIBLE
            profileProviderText?.visibility = View.GONE
            return
        }

        // Show provider icon if available, otherwise show provider name in parens
        val iconRes = providerIconRes(profile.providerLabel)
        if (iconRes != 0) {
            profileIconView?.setImageResource(iconRes)
            profileIconView?.visibility = View.VISIBLE
            profileIconView?.clearColorFilter()
            profileProviderText?.visibility = View.GONE
        } else if (profile.providerLabel != null) {
            profileIconView?.visibility = View.GONE
            profileProviderText?.text = " (${profile.providerLabel})"
            profileProviderText?.visibility = View.VISIBLE
        } else {
            profileIconView?.visibility = View.GONE
            profileProviderText?.visibility = View.GONE
        }
    }

    private fun providerIconRes(label: String?): Int = MusicProviderIcons.iconRes(label)

    /** Toggle media browser when profile name is tapped */
    private fun toggleMediaBrowserWithProfile() {
        toggleMediaBrowser()
    }

    fun destroy() {
        scrollAnimatorTrack?.cancel()
        scrollAnimatorTrack = null
        scrollAnimatorArtist?.cancel()
        scrollAnimatorArtist = null
        mediaBrowserPanel = null
        speakerDrawer = null
        speakerDrawerVisible = false
    }

    /** Transport control button — transparent bg, same as normal player. */
    private fun createControlButton(iconRes: Int, sizeDp: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            val size = dp(sizeDp)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(MusicPlayerStyles.textPrimary(context))
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { onClick() }
        }
    }

    /** Smaller toggle button (shuffle/repeat). Off = secondary tint, on = accent. */
    private fun createToggleButton(iconRes: Int, sizeDp: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            val size = dp(sizeDp)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(5), dp(5), dp(5), dp(5))
            MusicPlayerStyles.applyToggleButtonState(this, active = false)
            setOnClickListener { onClick() }
        }
    }
}

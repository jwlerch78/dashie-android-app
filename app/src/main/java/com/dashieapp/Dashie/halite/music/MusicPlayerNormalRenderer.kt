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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Renders the normal (expanded) music player view.
 * Album art + track/artist + prev/play/next + progress + collapsible volume.
 */
class MusicPlayerNormalRenderer(
    private val context: android.content.Context,
    private val callbacks: MusicPlayerCallbacks,
    private val onUserAction: () -> Unit
) {

    companion object {
        private const val TAG = "MusicPlayerNormalRndr"
    }

    // View references
    private var albumArtNormal: ImageView? = null
    private var albumArtContainer: FrameLayout? = null
    private var maximizeButtonNormal: ImageButton? = null
    private var trackTextNormal: TextView? = null
    private var artistTextNormal: TextView? = null
    private var playPauseButtonNormal: ImageButton? = null
    private var playPauseSpinner: android.view.View? = null
    private var prevButtonNormal: ImageButton? = null
    private var nextButtonNormal: ImageButton? = null
    private var shuffleButtonNormal: ImageButton? = null
    private var repeatButtonNormal: ImageButton? = null
    private var closeButtonNormal: ImageButton? = null
    private var minimizeButtonNormal: ImageButton? = null
    private var progressBar: SeekBar? = null
    private var isUserScrubbing: Boolean = false
    private var positionText: TextView? = null
    private var durationText: TextView? = null

    // Volume section
    private var volumeBarNormal: MusicVolumeBar? = null
    private var volumeCollapsed = true
    private var volumeSection: LinearLayout? = null
    private var speakerNameNormal: TextView? = null
    /** Override speaker bar text (set by drawer when group/transfer is active). */
    private var speakerBarOverride: String? = null

    // Media browser panel (replaces recently played strip)
    private var mediaBrowserPanel: MediaBrowserPanel? = null
    private var hasRecentlyPlayed = false
    private var isCurrentlyPlaying = false

    // Speaker group drawer (shares slot with recently played)
    internal var speakerDrawer: SpeakerGroupDrawer? = null
    var speakerDrawerVisible = false
        private set

    /** First recently played item, shown in idle state as the default suggestion. */
    var idleSuggestion: RecentlyPlayedItem? = null
    private var isCurrentlyIdle = false

    // Speaker row (below browser/drawer, always visible)
    private var speakerRow: LinearLayout? = null
    private var speakerBarIcon: ImageView? = null
    private var speakerToggleButton: ImageButton? = null  // expand/collapse on right side
    private var mediaSelectorButton: ImageButton? = null   // media browser toggle
    private var speakerButton: ImageButton? = null          // speaker drawer toggle
    private var mediaBrowserVisible = false

    // Persisted drawer height (shared with strip renderer)
    private val stripPrefs = context.getSharedPreferences("dashie_music_strip", android.content.Context.MODE_PRIVATE)
    private var customDrawerHeightDp: Int = stripPrefs.getInt("drawer_height_dp", SpeakerGroupDrawer.DRAWER_HEIGHT_DP)

    // Scroll animation
    var trackScrollViewNormal: HorizontalScrollView? = null
        private set
    var trackTextView: TextView? = null
        private set
    var scrollAnimatorNormal: ValueAnimator? = null
    var artistScrollViewNormal: HorizontalScrollView? = null
        private set
    var artistTextView: TextView? = null
        private set
    var scrollAnimatorArtist: ValueAnimator? = null

    /** Called when the volume section toggles, with the new height in dp. */
    var onHeightChanged: ((Int) -> Unit)? = null

    private fun dp(dp: Int) = MusicPlayerStyles.dpToPx(context, dp)

    val isVolumeCollapsed: Boolean get() = volumeCollapsed

    val heightDp: Int
        get() {
            if (volumeCollapsed) return MusicPlayerStyles.NORMAL_HEIGHT_COLLAPSED_DP
            val base = MusicPlayerStyles.NORMAL_HEIGHT_DP
            val extraHeight = when {
                speakerDrawerVisible -> customDrawerHeightDp
                mediaBrowserVisible && hasRecentlyPlayed -> customDrawerHeightDp
                else -> 0
            }
            return base + extraHeight + MusicPlayerStyles.SPEAKER_ROW_HEIGHT_DP
        }

    /**
     * Build normal mode views and add them to the parent FrameLayout.
     */
    fun render(parent: FrameLayout) {
        val outerWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MusicPlayerStyles.createRoundedBackground(context)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(MusicPlayerStyles.PADDING_DP), dp(MusicPlayerStyles.PADDING_DP),
                       dp(MusicPlayerStyles.PADDING_DP), dp(MusicPlayerStyles.PADDING_DP))
        }

        // Album art — square, dynamically resized by adjustArtSize() during resize
        val artSize = dp(MusicPlayerStyles.ART_SIZE_NORMAL_DP)
        albumArtContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                marginStart = dp(30)
                marginEnd = dp(12)
            }
        }

        albumArtNormal = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
        }
        albumArtContainer?.addView(albumArtNormal)
        container.addView(albumArtContainer)

        // Right column: Track/Artist + Controls + Progress
        val rightColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Track name with smooth scrolling (padded right to avoid upper-right buttons)
        trackScrollViewNormal = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, dp(68), 0)
        }
        trackTextNormal = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textPrimary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        trackTextView = trackTextNormal
        trackScrollViewNormal?.addView(trackTextNormal)
        rightColumn.addView(trackScrollViewNormal)

        // Artist name with smooth scrolling (padded right to avoid upper-right buttons)
        artistScrollViewNormal = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, dp(68), 0)
        }
        artistTextNormal = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textSecondary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        }
        artistTextView = artistTextNormal
        artistScrollViewNormal?.addView(artistTextNormal)
        rightColumn.addView(artistScrollViewNormal)

        // Playback controls
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Hide shuffle/repeat on screensaver panel — keep controls minimal.
        val showShuffleRepeat = !MusicPlayerStyles.forceDarkMode
        if (showShuffleRepeat) {
            shuffleButtonNormal = createToggleButton(com.dashieapp.Dashie.R.drawable.ic_shuffle, 32) {
                callbacks.onShuffleToggle?.invoke()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
            buttonContainer.addView(shuffleButtonNormal)
        }

        prevButtonNormal = createControlButton(com.dashieapp.Dashie.R.drawable.ic_previous, 40) {
            callbacks.onPrevious()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4) }
        buttonContainer.addView(prevButtonNormal)

        val playPauseSize = dp(48)
        val playPauseWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(playPauseSize, playPauseSize).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
        }
        playPauseButtonNormal = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(playPauseSize, playPauseSize)
            background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                if (isCurrentlyIdle && idleSuggestion != null) {
                    // Play the suggested recently played item
                    Log.i(TAG, "🎵 Idle suggestion play tapped: ${idleSuggestion!!.name} uri=${idleSuggestion!!.uri}")
                    callbacks.onPlayRecentItem?.invoke(idleSuggestion!!.uri)
                } else {
                    onUserAction()
                    callbacks.onPlayPause()
                }
            }
        }
        playPauseWrapper.addView(playPauseButtonNormal)
        playPauseSpinner = MusicPlayerStyles.createReconnectingSpinner(context, playPauseSize)
        playPauseWrapper.addView(playPauseSpinner)
        buttonContainer.addView(playPauseWrapper)

        nextButtonNormal = createControlButton(com.dashieapp.Dashie.R.drawable.ic_next, 40) {
            callbacks.onNext()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(4) }
        buttonContainer.addView(nextButtonNormal)

        if (showShuffleRepeat) {
            repeatButtonNormal = createToggleButton(com.dashieapp.Dashie.R.drawable.ic_repeat, 32) {
                callbacks.onRepeatCycle?.invoke()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
            buttonContainer.addView(repeatButtonNormal)
        }

        rightColumn.addView(buttonContainer)

        // Progress row
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        positionText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.textSecondary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); text = "0:00"
        }
        progressRow.addView(positionText)

        progressBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
            max = 1; progress = 0
            progressDrawable = MusicPlayerStyles.createProgressDrawable(context)
            // Both View.setMaxHeight(int) and View.setMinHeight(int) were
            // added in API 29 — older Fire OS / Android 9 devices throw
            // NoSuchMethodError when R8 resolves the call against the View-
            // level setter (the inherited ProgressBar.setMaxHeight isn't
            // picked up after minification). Skip on API < 29; the
            // progressDrawable bounds keep the bar visually consistent.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                maxHeight = dp(3)
                minHeight = dp(3)
            }
            thumb = MusicPlayerStyles.createSeekThumb(context, sizeDp = 12)
            val vPad = dp(8)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); text = "0:00"
        }
        progressRow.addView(durationText)

        rightColumn.addView(progressRow)
        container.addView(rightColumn)

        // Container takes all remaining vertical space (album art + controls grow with resize)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        outerWrapper.addView(container)

        // Collapsible volume section
        buildVolumeSection(outerWrapper)

        // Media browser panel (below volume, hidden until data arrives)
        mediaBrowserPanel = MediaBrowserPanel(
            context = context,
            onItemTapped = { item ->
                Log.i(TAG, "🎵 Browser callback: playing ${item.name} uri=${item.uri}")
                callbacks.onPlayRecentItem?.invoke(item.uri)
            },
            onHeightChanged = { onHeightChanged?.invoke(heightDp) }
        )
        outerWrapper.addView(mediaBrowserPanel!!.view)

        // Speaker group drawer (shares slot with recently played, hidden by default)
        speakerDrawer = SpeakerGroupDrawer(
            context = context,
            onClose = { toggleSpeakerDrawer() },
            onSpeakerToggle = { playerId, join -> callbacks.onSpeakerJoin?.invoke(playerId, join) },
            onSpeakerVolumeChange = { playerId, pct -> callbacks.onSpeakerVolumeChange?.invoke(playerId, pct) },
            onSpeakerMuteToggle = { playerId, muted -> callbacks.onSpeakerMuteToggle?.invoke(playerId, muted) },
            onSpeakerBarTextChanged = { text -> speakerBarOverride = text; speakerNameNormal?.text = text },
            onTransferQueue = { id, name -> callbacks.onTransferQueue?.invoke(id, name) },
            onClearQueue = { id -> callbacks.onClearQueue?.invoke(id) },
            onGroupVolumeChange = { pct -> callbacks.onGroupVolumeChange?.invoke(pct) },
            onGroupMuteToggle = { muted -> callbacks.onGroupMuteToggle?.invoke(muted) }
        )
        outerWrapper.addView(speakerDrawer!!.view)

        // Speaker row (always visible at bottom, with resize handle + expand/collapse toggle)
        buildSpeakerRow(outerWrapper)

        parent.addView(outerWrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Hide close/minimize/maximize when on screensaver panel
        val isOnPanel = MusicPlayerStyles.forceDarkMode

        val btnSize = dp(42)
        val btnPad = dp(9)
        val isDark = MusicPlayerStyles.isDarkMode(context)
        val btnBg = if (isDark) MusicPlayerStyles.CLOSE_BG_DARK else MusicPlayerStyles.CLOSE_BG_LIGHT
        val btnFg = if (isDark) MusicPlayerStyles.CLOSE_ICON_DARK else MusicPlayerStyles.CLOSE_ICON_LIGHT

        if (!isOnPanel) {
            // Close button - upper left
            closeButtonNormal = ImageButton(context).apply {
                layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = dp(6); leftMargin = dp(6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(btnBg)
                }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
                setColorFilter(btnFg)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(btnPad, btnPad, btnPad, btnPad)
                isClickable = true; isFocusable = true
                elevation = dp(10).toFloat()
                setOnClickListener { showCloseModal() }
            }
            parent.addView(closeButtonNormal)

            // Upper right button row: minimize (-) then maximize (expand)
            val topRightRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(6); rightMargin = dp(6)
                }
                elevation = dp(10).toFloat()
            }

            minimizeButtonNormal = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = dp(6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(btnBg)
                }
                setColorFilter(btnFg)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_minimize)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(btnPad, btnPad, btnPad, btnPad)
                isClickable = true; isFocusable = true
                setOnClickListener { callbacks.onMinimize() }
            }
            topRightRow.addView(minimizeButtonNormal)

            maximizeButtonNormal = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(btnBg)
                }
                setColorFilter(btnFg)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_expand)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(btnPad, btnPad, btnPad, btnPad)
                isClickable = true; isFocusable = true
                setOnClickListener { callbacks.onMaximize() }
            }
            topRightRow.addView(maximizeButtonNormal)

            parent.addView(topRightRow)
        }
    }

    private fun buildVolumeSection(outerWrapper: LinearLayout) {
        val volumeSectionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (volumeCollapsed) View.GONE else View.VISIBLE
        }

        // Divider
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { marginStart = dp(12); marginEnd = dp(12) }
            setBackgroundColor(if (MusicPlayerStyles.isDarkMode(context))
                MusicPlayerStyles.BORDER_COLOR_DARK else MusicPlayerStyles.BORDER_COLOR_LIGHT)
            volumeSectionLayout.addView(this)
        }

        val volumeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        volumeBarNormal = MusicVolumeBar(
            context = context, scale = 1f,
            accentColor = MusicPlayerStyles.ACCENT_COLOR,
            textColor = MusicPlayerStyles.textSecondary(context),
            iconColor = MusicPlayerStyles.textSecondary(context),
            onVolumeChange = callbacks.onVolumeChange
        ).also {
            it.view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            volumeRow.addView(it.view)
        }

        volumeSectionLayout.addView(volumeRow)
        volumeSection = volumeSectionLayout
        outerWrapper.addView(volumeSectionLayout)
    }

    private fun buildSpeakerRow(outerWrapper: LinearLayout) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply {
                setColor(0x80000000.toInt())
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dp(MusicPlayerStyles.CORNER_RADIUS_DP).toFloat(),
                    dp(MusicPlayerStyles.CORNER_RADIUS_DP).toFloat(),
                    dp(MusicPlayerStyles.CORNER_RADIUS_DP).toFloat(),
                    dp(MusicPlayerStyles.CORNER_RADIUS_DP).toFloat()
                )
            }
        }

        // Resize handle — bottom left (hidden on screensaver panel)
        if (!MusicPlayerStyles.forceDarkMode) {
            val handleSize = dp(MusicPlayerStyles.RESIZE_HANDLE_DP)
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(handleSize, handleSize).apply { marginEnd = dp(2) }
                setImageDrawable(androidx.core.content.ContextCompat.getDrawable(
                    context, com.dashieapp.Dashie.R.drawable.ic_resize_handle))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                row.addView(this)
            }
        }

        // Media selector button (left of speaker area) — hidden on screensaver
        if (!MusicPlayerStyles.forceDarkMode) {
            mediaSelectorButton = ImageButton(context).apply {
                val size = dp(28)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true; isFocusable = true
                setOnClickListener { toggleMediaBrowser() }
            }
            row.addView(mediaSelectorButton)
        }

        // Speaker section (center, fills space)
        val speakerClickArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleSpeakerDrawer() }
        }
        speakerBarIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_box_speaker)
            setColorFilter(Color.WHITE)
            speakerClickArea.addView(this)
        }
        speakerNameNormal = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = ""
        }
        speakerClickArea.addView(speakerNameNormal)
        row.addView(speakerClickArea)

        // Speaker button and expand/collapse toggle — hidden on screensaver
        if (!MusicPlayerStyles.forceDarkMode) {
            speakerButton = ImageButton(context).apply {
                val size = dp(28)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_box_speaker)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true; isFocusable = true
                setOnClickListener { toggleSpeakerDrawer() }
            }
            row.addView(speakerButton)

            speakerToggleButton = ImageButton(context).apply {
                val size = dp(24)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
                setBackgroundColor(Color.TRANSPARENT)
                setImageDrawable(MusicPlayerStyles.createTriangleDrawable(
                    context, pointingUp = !volumeCollapsed, color = Color.WHITE))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (speakerDrawerVisible) {
                        toggleSpeakerDrawer()
                    } else if (mediaBrowserVisible) {
                        toggleMediaBrowser()
                    } else {
                        toggleVolumeSection()
                    }
                }
            }
            row.addView(speakerToggleButton)
        }

        speakerRow = row
        outerWrapper.addView(row)
    }

    private fun showCloseModal() {
        if (!isCurrentlyPlaying) {
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

    fun toggleSpeakerDrawer() {
        speakerDrawerVisible = !speakerDrawerVisible
        if (speakerDrawerVisible) {
            if (volumeCollapsed) toggleVolumeSection()
            speakerDrawer?.view?.visibility = View.VISIBLE
            mediaBrowserPanel?.view?.visibility = View.GONE
            mediaBrowserVisible = false
            applyDrawerHeight()
        } else {
            speakerDrawer?.view?.visibility = View.GONE
        }
        updateToggleButtonColors()
        callbacks.onSpeakerDrawerToggle?.invoke()
        onHeightChanged?.invoke(heightDp)
    }

    private fun toggleMediaBrowser() {
        if (speakerDrawerVisible) {
            speakerDrawerVisible = false
            speakerDrawer?.view?.visibility = View.GONE
            callbacks.onSpeakerDrawerToggle?.invoke()
        }
        mediaBrowserVisible = !mediaBrowserVisible
        if (mediaBrowserVisible) {
            if (volumeCollapsed) toggleVolumeSection()
            mediaBrowserPanel?.view?.visibility = View.VISIBLE
            applyDrawerHeight()
        } else {
            mediaBrowserPanel?.view?.visibility = View.GONE
        }
        updateToggleButtonColors()
        onHeightChanged?.invoke(heightDp)
    }

    private fun updateToggleButtonColors() {
        val defaultColor = if (MusicPlayerStyles.isDarkMode(context))
            MusicPlayerStyles.CLOSE_ICON_DARK else MusicPlayerStyles.CLOSE_ICON_LIGHT
        speakerButton?.setColorFilter(
            if (speakerDrawerVisible) MusicPlayerStyles.ACCENT_COLOR else defaultColor
        )
        mediaSelectorButton?.setColorFilter(
            if (mediaBrowserVisible) MusicPlayerStyles.ACCENT_COLOR else defaultColor
        )
    }

    private fun applyDrawerHeight() {
        val heightPx = dp(customDrawerHeightDp)
        if (speakerDrawerVisible) {
            speakerDrawer?.view?.layoutParams?.height = heightPx
            speakerDrawer?.view?.requestLayout()
        }
        if (mediaBrowserVisible) {
            mediaBrowserPanel?.view?.let { v ->
                v.layoutParams?.height = heightPx
                (v as? android.widget.FrameLayout)?.getChildAt(0)?.let { child ->
                    child.layoutParams?.height = heightPx
                    child.requestLayout()
                }
                v.requestLayout()
            }
        }
    }

    /** Update the speaker drawer with speaker data from MA API. */
    fun updateSpeakerDrawer(speakers: List<SpeakerGroupDrawer.SpeakerInfo>, groups: List<SpeakerGroupDrawer.GroupInfo> = emptyList(), thisDeviceId: String = "", currentTargetId: String = "", defaultEntityId: String = "") {
        if (thisDeviceId.isNotEmpty()) speakerDrawer?.thisDevicePlayerId = thisDeviceId
        if (defaultEntityId.isNotEmpty()) speakerDrawer?.defaultEntityId = defaultEntityId
        if (currentTargetId.isNotEmpty()) speakerDrawer?.setCurrentlyPlaying(currentTargetId)
        speakerDrawer?.update(speakers, groups)
        speakerDrawer?.getFormattedSpeakerBarText()?.takeIf { it.isNotEmpty() }?.let {
            speakerBarOverride = it
            speakerNameNormal?.text = it
        }
        updateSpeakerBarIcon()
    }

    /** Update volume for a specific speaker in the drawer. */
    fun updateSpeakerVolume(playerId: String, volumePercent: Int) {
        speakerDrawer?.updateSpeakerVolume(playerId, volumePercent)
    }

    fun configureVisibilityStore(haBaseUrl: String, haAuthToken: String) {
        speakerDrawer?.visibilityStore?.apply {
            this.haBaseUrl = haBaseUrl
            this.haAuthToken = haAuthToken
            fetchGlobalFromHa()
        }
    }

    /** Update current target after transfer and refresh speaker bar. */
    fun updateSpeakerDrawerCurrentTarget(targetPlayerId: String) {
        speakerDrawer?.setCurrentlyPlaying(targetPlayerId)
        speakerDrawer?.selectTarget(targetPlayerId)
        speakerDrawer?.getFormattedSpeakerBarText()?.takeIf { it.isNotEmpty() }?.let {
            speakerBarOverride = it
            speakerNameNormal?.text = it
        }
        updateSpeakerBarIcon()
    }

    private fun updateSpeakerBarIcon() {
        val isGroup = speakerDrawer?.isCurrentTargetGroup() == true
        speakerBarIcon?.setImageResource(
            if (isGroup) com.dashieapp.Dashie.R.drawable.ic_speaker_group
            else com.dashieapp.Dashie.R.drawable.ic_box_speaker
        )
    }

    /** Get the speaker drawer bounds for touch passthrough. */
    fun getSpeakerDrawerBounds(card: View): Rect? {
        return speakerDrawer?.getBounds(card)
    }

    private fun toggleVolumeSection() {
        volumeCollapsed = !volumeCollapsed
        val vis = if (volumeCollapsed) View.GONE else View.VISIBLE
        volumeSection?.visibility = vis
        // Browser panel/drawer shows/hides with volume section
        if (speakerDrawerVisible) {
            speakerDrawer?.view?.visibility = vis
        } else if (mediaBrowserVisible) {
            mediaBrowserPanel?.view?.visibility = vis
        }
        // Close drawers when collapsing
        if (volumeCollapsed) {
            if (speakerDrawerVisible) {
                speakerDrawerVisible = false
                speakerDrawer?.view?.visibility = View.GONE
            }
            if (mediaBrowserVisible) {
                mediaBrowserVisible = false
                mediaBrowserPanel?.view?.visibility = View.GONE
            }
            updateToggleButtonColors()
        }
        speakerToggleButton?.setImageDrawable(MusicPlayerStyles.createTriangleDrawable(
            context, pointingUp = !volumeCollapsed, color = Color.WHITE))
        onHeightChanged?.invoke(heightDp)
    }

    /**
     * Expand the volume section programmatically (e.g., restoring state after theme change).
     */
    fun expandVolumeSection() {
        if (volumeCollapsed) {
            toggleVolumeSection()
        }
    }

    fun updateViews(data: MusicPlayerData) {
        val isIdleConnected = !data.hasMedia && !data.isReconnecting && data.entityId.isNotEmpty()
        isCurrentlyIdle = isIdleConnected
        isCurrentlyPlaying = data.isPlaying

        if (isIdleConnected) {
            val suggestion = idleSuggestion
            if (suggestion != null) {
                // Show the first recently played item as the current suggestion
                when (suggestion.mediaType.lowercase()) {
                    "artist" -> {
                        trackTextNormal?.text = "(Artist) ${suggestion.name}"
                        artistTextNormal?.text = "Recently played artist"
                    }
                    "album" -> {
                        trackTextNormal?.text = "(Album) ${suggestion.name}"
                        artistTextNormal?.text = suggestion.artist ?: ""
                    }
                    "playlist" -> {
                        trackTextNormal?.text = "(Playlist) ${suggestion.name}"
                        artistTextNormal?.text = "Recently played playlist"
                    }
                    else -> {
                        trackTextNormal?.text = suggestion.name
                        artistTextNormal?.text = suggestion.artist ?: "Recently played"
                    }
                }
            } else {
                trackTextNormal?.text = "No music playing"
                artistTextNormal?.text = "Select a song from the media library"
            }
            if (!isUserScrubbing) {
                positionText?.text = "0:00"
                durationText?.text = "0:00"
                progressBar?.max = 1
                progressBar?.progress = 0
            }
            progressBar?.isEnabled = false
            // Play button is active — pressing it plays the suggestion
            playPauseButtonNormal?.setImageResource(com.dashieapp.Dashie.R.drawable.ic_play)
            playPauseButtonNormal?.setColorFilter(Color.WHITE)
            if (suggestion != null) {
                playPauseButtonNormal?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
                playPauseButtonNormal?.isClickable = true
            } else {
                playPauseButtonNormal?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
                playPauseButtonNormal?.isClickable = false
            }
            playPauseSpinner?.visibility = View.GONE
        } else {
            if (data.isReconnecting) {
                trackTextNormal?.text = "Reconnecting"
                artistTextNormal?.text = "Music Assistant"
            } else {
                trackTextNormal?.text = data.trackName.ifBlank { "Unknown Track" }
                artistTextNormal?.text = data.artistName.ifBlank { "Unknown Artist" }
            }
            if (!isUserScrubbing) {
                positionText?.text = data.formattedPosition
                durationText?.text = data.formattedDuration
                progressBar?.max = data.durationSeconds.coerceAtLeast(1)
                progressBar?.progress = data.positionSeconds
            }
            progressBar?.isEnabled = !data.isReconnecting && data.durationSeconds > 0

            if (data.isReconnecting) {
                playPauseButtonNormal?.setImageDrawable(null)
                playPauseButtonNormal?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
                playPauseButtonNormal?.isClickable = false
                playPauseSpinner?.visibility = View.VISIBLE
            } else {
                playPauseButtonNormal?.setImageResource(
                    if (data.isPlaying) com.dashieapp.Dashie.R.drawable.ic_pause
                    else com.dashieapp.Dashie.R.drawable.ic_play
                )
                playPauseButtonNormal?.setColorFilter(Color.WHITE)
                playPauseButtonNormal?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
                playPauseButtonNormal?.isClickable = true
                playPauseSpinner?.visibility = View.GONE
            }
        }

        volumeBarNormal?.updateVolume(data.volumeLevel, data.isVolumeMuted, data.isVolumeUnavailable)

        shuffleButtonNormal?.let {
            MusicPlayerStyles.applyToggleButtonState(it, data.shuffleEnabled)
        }
        repeatButtonNormal?.let {
            it.setImageResource(
                if (data.repeatMode == "one") com.dashieapp.Dashie.R.drawable.ic_repeat_one
                else com.dashieapp.Dashie.R.drawable.ic_repeat
            )
            MusicPlayerStyles.applyToggleButtonState(it, data.repeatMode != "off")
        }

        // Speaker name: drawer override > persisted bar text > data.friendlyName
        if (speakerBarOverride == null) {
            val persisted = speakerDrawer?.getPersistedBarText()?.takeIf { it.isNotEmpty() }
            speakerNameNormal?.text = persisted ?: data.friendlyName.ifBlank {
                if (data.entityId.startsWith("media_player.")) {
                    data.entityId.removePrefix("media_player.").replace("_", " ")
                } else {
                    android.os.Build.MODEL
                }
            }
        }
    }

    fun getAlbumArtView(): ImageView? = albumArtNormal

    fun getClickableButtons(): List<ImageButton> = listOfNotNull(
        playPauseButtonNormal, prevButtonNormal, nextButtonNormal,
        shuffleButtonNormal, repeatButtonNormal,
        closeButtonNormal, minimizeButtonNormal, maximizeButtonNormal,
        speakerToggleButton
    )

    /**
     * Get the volume section bounds relative to the card, for touch passthrough.
     */
    fun getVolumeSectionBounds(card: View): Rect? {
        val volSection = volumeSection ?: return null
        if (volSection.visibility != View.VISIBLE || volSection.height <= 0) return null
        val loc = IntArray(2)
        volSection.getLocationInWindow(loc)
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        val relY = loc[1] - cardLoc[1]
        return Rect(0, relY, card.width, relY + volSection.height)
    }

    fun updateRecentlyPlayed(data: RecentlyPlayedData) {
        hasRecentlyPlayed = data.items.isNotEmpty()
        mediaBrowserPanel?.update(data)
        // Panel stays hidden until user opens it via media selector button
        if (!mediaBrowserVisible) {
            mediaBrowserPanel?.view?.visibility = View.GONE
        }
    }

    /** Set the data source for library browsing on the media browser panel. */
    fun setMediaBrowserDataSource(ds: MediaBrowserDataSource) {
        mediaBrowserPanel?.setDataSource(ds)
    }

    fun getRecentlyPlayedBounds(card: View): Rect? {
        return mediaBrowserPanel?.getBounds(card)
    }

    fun getSpeakerRowBounds(card: View): Rect? {
        val row = speakerRow ?: return null
        if (row.visibility != View.VISIBLE || row.height <= 0) return null
        val loc = IntArray(2)
        row.getLocationInWindow(loc)
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        val relY = loc[1] - cardLoc[1]
        return Rect(0, relY, card.width, relY + row.height)
    }


    fun destroy() {
        scrollAnimatorNormal?.cancel()
        scrollAnimatorNormal = null
        scrollAnimatorArtist?.cancel()
        scrollAnimatorArtist = null
        volumeBarNormal?.destroy()
        mediaBrowserPanel = null
        speakerDrawer = null
        speakerDrawerVisible = false
    }

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

    /** Smaller toggle button (shuffle/repeat) — secondary tint when off, accent when on. */
    private fun createToggleButton(iconRes: Int, sizeDp: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            val size = dp(sizeDp)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            MusicPlayerStyles.applyToggleButtonState(this, active = false)
            setOnClickListener { onClick() }
        }
    }
}

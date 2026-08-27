package com.dashieapp.Dashie.halite.music

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Player content panel: album art, track/artist text, playback controls,
 * progress bar, and bottom toolbar (playlist, volume, speaker).
 * Used by MusicPlayerMaximizedRenderer in full-width and split-view layouts.
 */
class MaximizedPlayerControls(
    private val context: android.content.Context,
    private val availableWidth: Int,
    private val availableHeight: Int,
    private val callbacks: MusicPlayerCallbacks,
    private val onUserAction: () -> Unit,
    private val constrainedWidth: Int? = null,
    private val showSpeakerIndicator: Boolean = true,
    private val showPlaylistButton: Boolean = false,
    private val showPlaylistCloseBadge: Boolean = false,
    private val reserveBottomDp: Int = 0
) {

    // View references
    private var albumArt: ImageView? = null
    private var trackText: TextView? = null
    private var artistText: TextView? = null
    private var playPauseButton: ImageButton? = null
    private var prevButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var shuffleButton: ImageButton? = null
    private var repeatButton: ImageButton? = null
    private var progressBar: SeekBar? = null
    private var isUserScrubbing: Boolean = false
    private var positionText: TextView? = null
    private var durationText: TextView? = null
    private var volumeBar: MusicVolumeBar? = null
    private var volumeToggleButton: ImageButton? = null
    private var volumeToggleLabel: TextView? = null
    private var hasActiveGroup: Boolean = false
    /** Coordinator for shared volume mode state. */
    var volumeCoordinator: MusicStateCoordinator? = null
    private val isGroupVolumeMode: Boolean get() = volumeCoordinator?.isGroupVolumeMode != false
    private var playPauseSpinner: View? = null
    private var speakerName: TextView? = null
    private var speakerIcon: ImageView? = null
    private var speakerCountBadge: TextView? = null
    private var playlistButton: ImageButton? = null
    private var speakerButton: ImageButton? = null
    private var mediaProfileLabel: TextView? = null

    /** Set the idle suggestion item for display when no media is playing. */
    var idleSuggestion: RecentlyPlayedItem? = null

    /** Update the media profile display (provider logo + name) */
    fun updateMediaProfile(profile: MusicProfile?) {
        if (profile == null) return
        val iconRes = MusicProviderIcons.iconRes(profile.providerLabel)
            .takeIf { it != 0 } ?: com.dashieapp.Dashie.R.drawable.ic_type_playlist
        playlistButton?.setImageResource(iconRes)
        playlistButton?.clearColorFilter()
        mediaProfileLabel?.text = "${profile.memberName}'s Music"
    }

    private fun dp(dp: Int) = MusicPlayerStyles.dpToPx(context, dp)

    private fun formatSec(seconds: Int): String =
        "%d:%02d".format(seconds / 60, seconds % 60)

    fun build(): LinearLayout {
        val effectiveWidth = constrainedWidth ?: availableWidth
        val isSmallScreen = availableHeight < dp(600)
        val isConstrained = constrainedWidth != null
        // Large screen: tall display like the Mio 15" (1080px at 160dpi)
        val isLargeScreen = availableHeight > dp(900)
        // Use small sizes only when screen is short or constrained panel is narrow
        val useSmallSizes = isSmallScreen || (isConstrained && effectiveWidth < dp(400))
        // Narrow portrait: tablet in portrait where horizontal space is tight
        // (Fire tablet ~601dp, Samsung SM-X200 portrait). Echo Show 5 at ~788dp
        // stays on the wide/normal layout.
        val isNarrowPortrait = !isConstrained && effectiveWidth < dp(750)

        val artHeightFrac = if (isNarrowPortrait) 0.28 else 0.35
        val maxArtByHeight = (availableHeight * artHeightFrac).toInt()
        val maxArtByWidth = (effectiveWidth * 0.80).toInt()
        val artSize = minOf(maxArtByHeight, maxArtByWidth, dp(MusicPlayerStyles.ART_SIZE_MAXIMIZED_DP))

        val padding = if (useSmallSizes) dp(16) else if (isLargeScreen) dp(40) else dp(32)
        val topPadding = if (useSmallSizes) dp(24) else if (isLargeScreen) dp(56) else dp(48)
        // When reserving bottom space for strip overlay, use it as container bottom padding
        val bottomPad = if (reserveBottomDp > 0) dp(reserveBottomDp) else padding

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, topPadding, padding, bottomPad)
        }

        // Top spacer — pushes content toward center (reduced in narrow portrait
        // so the album + transport + progress + volume stack slide up, leaving
        // room for the new dedicated volume row under the timebar).
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0,
                if (isNarrowPortrait) 0.5f else 1f)
        })

        // Album art
        albumArt = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                gravity = Gravity.CENTER
                bottomMargin = if (useSmallSizes) dp(12) else if (isLargeScreen) dp(32) else dp(24)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
        }
        container.addView(albumArt)

        // Track name
        trackText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.TEXT_PRIMARY_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (useSmallSizes) 20f else if (isLargeScreen) 28f else 24f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        container.addView(trackText)

        // Artist name
        artistText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.TEXT_SECONDARY_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (useSmallSizes) 14f else if (isLargeScreen) 22f else 18f)
            typeface = Typeface.DEFAULT
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(artistText)

        // Spacer — equal space above controls (between artist and play button)
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // Playback controls
        val prevNextSize = if (useSmallSizes) dp(44) else if (isLargeScreen) dp(72) else dp(56)
        val playPauseSize = if (useSmallSizes) dp(56) else if (isLargeScreen) dp(90) else dp(72)
        val shuffleRepeatSize = if (useSmallSizes) dp(36) else if (isLargeScreen) dp(56) else dp(44)
        val buttonMargin = if (useSmallSizes) dp(8) else if (isLargeScreen) dp(16) else dp(12)
        val outerMargin = if (useSmallSizes) dp(12) else if (isLargeScreen) dp(24) else dp(18)

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (useSmallSizes) dp(8) else dp(12) }
        }

        // Hide shuffle/repeat on screensaver panel (showSpeakerIndicator=false)
        // to keep the controls minimal there.
        val showShuffleRepeat = showSpeakerIndicator
        if (showShuffleRepeat) {
            shuffleButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(shuffleRepeatSize, shuffleRepeatSize).apply {
                    marginEnd = outerMargin
                }
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_shuffle)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(6), dp(6), dp(6), dp(6))
                MusicPlayerStyles.applyToggleButtonState(this, active = false, forceDarkMode = true)
                setOnClickListener { callbacks.onShuffleToggle?.invoke() }
            }
            buttonContainer.addView(shuffleButton)
        }

        prevButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(prevNextSize, prevNextSize).apply {
                marginEnd = buttonMargin
            }
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(MusicPlayerStyles.TEXT_PRIMARY_DARK)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_previous)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { callbacks.onPrevious() }
        }
        buttonContainer.addView(prevButton)

        val playPauseWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(playPauseSize, playPauseSize).apply {
                marginStart = buttonMargin; marginEnd = buttonMargin
            }
        }
        playPauseButton = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(playPauseSize, playPauseSize)
            background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                onUserAction()
                callbacks.onPlayPause()
            }
        }
        playPauseWrapper.addView(playPauseButton)
        playPauseSpinner = MusicPlayerStyles.createReconnectingSpinner(context, playPauseSize)
        playPauseWrapper.addView(playPauseSpinner)
        buttonContainer.addView(playPauseWrapper)

        nextButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(prevNextSize, prevNextSize).apply {
                marginStart = buttonMargin
            }
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(MusicPlayerStyles.TEXT_PRIMARY_DARK)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_next)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { callbacks.onNext() }
        }
        buttonContainer.addView(nextButton)

        if (showShuffleRepeat) {
            repeatButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(shuffleRepeatSize, shuffleRepeatSize).apply {
                    marginStart = outerMargin
                }
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_repeat)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(6), dp(6), dp(6), dp(6))
                MusicPlayerStyles.applyToggleButtonState(this, active = false, forceDarkMode = true)
                setOnClickListener { callbacks.onRepeatCycle?.invoke() }
            }
            buttonContainer.addView(repeatButton)
        }

        container.addView(buttonContainer)

        // Progress row — width matches the transport button row so the
        // position/duration text edges line up with the shuffle/repeat icons.
        val shuffleRepeatExtra = if (showShuffleRepeat) 2 * shuffleRepeatSize + 2 * outerMargin else 0
        val transportTotalWidth = shuffleRepeatExtra +
            2 * prevNextSize + 2 * buttonMargin + playPauseSize
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                transportTotalWidth, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        positionText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.TEXT_SECONDARY_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); text = "0:00"
        }
        progressRow.addView(positionText)

        progressBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12); marginEnd = dp(12)
            }
            max = 1; progress = 0
            progressDrawable = MusicPlayerStyles.createProgressDrawable(context, forceDarkMode = true)
            // Constrain the bar to a thin line; the thumb sits on top of it.
            // setMaxHeight/setMinHeight only safe on API 29+ — see
            // MusicPlayerNormalRenderer for the same guard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                maxHeight = dp(4)
                minHeight = dp(4)
            }
            thumb = MusicPlayerStyles.createSeekThumb(context, sizeDp = 14)
            // Vertical padding keeps the touch radius generous despite the thin bar.
            // Horizontal padding gives the thumb room to draw at progress 0/max
            // without being clipped by the SeekBar's view bounds.
            val vPad = dp(8)
            val hPad = dp(7)
            setPadding(hPad, vPad, hPad, vPad)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        positionText?.text = formatSec(progress)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    isUserScrubbing = true
                    onUserAction()
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    isUserScrubbing = false
                    val sec = bar?.progress ?: 0
                    callbacks.onSeek?.invoke(sec * 1000L)
                }
            })
        }
        progressRow.addView(progressBar)

        durationText = TextView(context).apply {
            setTextColor(MusicPlayerStyles.TEXT_SECONDARY_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); text = "0:00"
        }
        progressRow.addView(durationText)

        container.addView(progressRow)

        // Spacer — equal space below controls (between progress bar and bottom toolbar)
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // --- Bottom toolbar: [playlist]  [volume centered]  [speaker] ---

        // Use volume button dp for consistent icon sizing
        val volumeButtonDp = if (useSmallSizes) 44 else if (isLargeScreen) 72 else 56
        // Playlist icon matches volume bar speaker icon size
        val playlistSize = MusicPlayerStyles.dpToPx(context, volumeButtonDp)

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Left: [provider logo] [Profile's Music] — opens media browser
        if (showPlaylistButton) {
            val mediaArea = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, playlistSize
                )
                isClickable = true; isFocusable = true
                setOnClickListener { callbacks.onExplorerToggle?.invoke() }
            }

            playlistButton = ImageButton(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = MusicPlayerStyles.dpToPx(context, volumeButtonDp / 8)
                setPadding(pad, pad, pad, pad)
                isClickable = false  // Parent handles click
                layoutParams = LinearLayout.LayoutParams(playlistSize, playlistSize).apply {
                    marginEnd = MusicPlayerStyles.dpToPx(context, 6)
                }
            }
            mediaArea.addView(playlistButton)

            mediaProfileLabel = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (useSmallSizes) 14f else if (isLargeScreen) 18f else 16f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = "Music"
            }
            mediaArea.addView(mediaProfileLabel)

            bottomRow.addView(mediaArea)
        }

        // Center: volume controls + group toggle icon (fills remaining space)
        // On screensaver (no speaker indicator), hide volume entirely to save space for bottom strip
        val isOnPanel = !showSpeakerIndicator
        if (!isOnPanel) {
            // In narrow portrait, volume gets its own full-width row between
            // the progress bar and the media/speaker selector row. In wide
            // mode it sits in the middle of the bottom toolbar (layoutParams
            // with weight=1 fills the remaining horizontal space).
            val volumeWrapper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = if (isNarrowPortrait) {
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                } else {
                    LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            }
            volumeBar = MusicVolumeBar(
                context = context, scale = 1f, buttonSizeDp = volumeButtonDp,
                accentColor = MusicPlayerStyles.ACCENT_COLOR,
                textColor = MusicPlayerStyles.TEXT_SECONDARY_DARK,
                iconColor = MusicPlayerStyles.TEXT_SECONDARY_DARK,
                onVolumeChange = { level ->
                    if (isGroupVolumeMode && hasActiveGroup) {
                        callbacks.onGroupVolumeChange?.invoke((level * 100).toInt())
                    } else {
                        callbacks.onVolumeChange?.invoke(level)
                    }
                }
            ).also {
                volumeWrapper.addView(it.view)
            }
            // Group/Speaker toggle — compact orange circle icon + label to the right of volume bar
            val toggleSize = if (useSmallSizes) dp(32) else if (isLargeScreen) dp(48) else dp(40)
            val toggleIconPad = if (useSmallSizes) dp(6) else if (isLargeScreen) dp(10) else dp(8)
            val toggleLabelSp = if (useSmallSizes) 9f else if (isLargeScreen) 12f else 10f
            val toggleColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
                visibility = View.GONE
                isClickable = true; isFocusable = true
                setOnClickListener { toggleVolumeMode() }
            }
            volumeToggleButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(toggleSize, toggleSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MusicPlayerStyles.ACCENT_COLOR)
                }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_speaker_group)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(toggleIconPad, toggleIconPad, toggleIconPad, toggleIconPad)
                alpha = 0.85f
                isClickable = false  // Parent column handles click
            }
            toggleColumn.addView(volumeToggleButton)
            volumeToggleLabel = TextView(context).apply {
                text = "Group"
                setTextColor(MusicPlayerStyles.TEXT_SECONDARY_DARK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, toggleLabelSp)
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            toggleColumn.addView(volumeToggleLabel)
            volumeWrapper.addView(toggleColumn)
            if (isNarrowPortrait) {
                // Narrow portrait: place volume in its own centered row between
                // the progress bar and the media/speaker bottom row. The row is
                // sandwiched between two weight=1 spacers so it sits at the
                // exact vertical midpoint of that gap.
                val volumeRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                volumeRow.addView(volumeWrapper)
                // Append volumeRow to the container — the existing "bottom spacer"
                // (added earlier, between progress and bottomRow) sits above it,
                // and we add a matching weight=1 spacer below so the row is
                // vertically centered between the timeline and bottomRow.
                container.addView(volumeRow)
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                })
                // bottomRow spacer so media profile and speaker indicator
                // separate to the edges (same as the screensaver/panel branch).
                bottomRow.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
            } else {
                bottomRow.addView(volumeWrapper)
            }
        } else {
            // Screensaver: no volume controls, just a spacer
            bottomRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }

        // Right: speaker indicator (in toolbar, not screensaver mode)
        if (showSpeakerIndicator) {
            val speakerIndicator = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isClickable = true; isFocusable = true
                setOnClickListener { callbacks.onSpeakerClicked?.invoke() }
            }
            val speakerIconSize = if (useSmallSizes) dp(20) else if (isLargeScreen) dp(28) else dp(24)
            speakerIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(speakerIconSize, speakerIconSize).apply { marginEnd = dp(6) }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_box_speaker)
                setColorFilter(MusicPlayerStyles.TEXT_SECONDARY_DARK)
                speakerIndicator.addView(this)
            }
            speakerName = TextView(context).apply {
                setTextColor(MusicPlayerStyles.TEXT_SECONDARY_DARK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (useSmallSizes) 14f else if (isLargeScreen) 18f else 16f)
                maxLines = 1
                maxWidth = dp(120)
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = ""
            }
            speakerIndicator.addView(speakerName)
            // Speaker count badge (orange circle)
            val badgeSize = if (useSmallSizes) dp(18) else if (isLargeScreen) dp(24) else dp(20)
            speakerCountBadge = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply { marginStart = dp(6) }
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isLargeScreen) 12f else 10f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MusicPlayerStyles.ACCENT_COLOR)
                }
                visibility = View.GONE
            }
            speakerIndicator.addView(speakerCountBadge)
            bottomRow.addView(speakerIndicator)
        }

        container.addView(bottomRow)

        return container
    }

    fun updateViews(data: MusicPlayerData) {
        val isIdleConnected = !data.hasMedia && !data.isReconnecting && data.entityId.isNotEmpty()

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
            prevButton?.alpha = 1.0f
            prevButton?.isClickable = true
            nextButton?.alpha = 1.0f
            nextButton?.isClickable = true
        } else if (data.isReconnecting) {
            trackText?.text = "Reconnecting"
            artistText?.text = "Music Assistant"
            if (!isUserScrubbing) {
                positionText?.text = data.formattedPosition
                durationText?.text = data.formattedDuration
                progressBar?.max = data.durationSeconds.coerceAtLeast(1)
                progressBar?.progress = data.positionSeconds
            }
            progressBar?.isEnabled = false
            playPauseButton?.setImageDrawable(null)
            playPauseButton?.background = MusicPlayerStyles.createCircleBackground(0x40808080.toInt())
            playPauseButton?.isClickable = false
            playPauseSpinner?.visibility = View.VISIBLE
            prevButton?.alpha = 0.3f
            prevButton?.isClickable = false
            nextButton?.alpha = 0.3f
            nextButton?.isClickable = false
        } else {
            trackText?.text = data.trackName.ifBlank { "Unknown Track" }
            artistText?.text = data.artistName.ifBlank { "Unknown Artist" }
            if (!isUserScrubbing) {
                positionText?.text = data.formattedPosition
                durationText?.text = data.formattedDuration
                progressBar?.max = data.durationSeconds.coerceAtLeast(1)
                progressBar?.progress = data.positionSeconds
            }
            progressBar?.isEnabled = data.durationSeconds > 0
            playPauseButton?.setImageResource(
                if (data.isPlaying) com.dashieapp.Dashie.R.drawable.ic_pause
                else com.dashieapp.Dashie.R.drawable.ic_play
            )
            playPauseButton?.setColorFilter(Color.WHITE)
            playPauseButton?.background = MusicPlayerStyles.createCircleBackground(MusicPlayerStyles.ACCENT_COLOR)
            playPauseButton?.isClickable = true
            playPauseSpinner?.visibility = View.GONE
            prevButton?.alpha = 1.0f
            prevButton?.isClickable = true
            nextButton?.alpha = 1.0f
            nextButton?.isClickable = true
        }

        volumeBar?.updateVolume(data.volumeLevel, data.isVolumeMuted, data.isVolumeUnavailable)

        applyShuffleRepeatState(data)

        speakerName?.text = data.friendlyName.ifBlank {
            data.entityId.removePrefix("media_player.").ifBlank { "" }
        }
    }

    private fun applyShuffleRepeatState(data: MusicPlayerData) {
        shuffleButton?.let {
            MusicPlayerStyles.applyToggleButtonState(it, data.shuffleEnabled, forceDarkMode = true)
        }
        repeatButton?.let {
            val active = data.repeatMode != "off"
            it.setImageResource(
                if (data.repeatMode == "one") com.dashieapp.Dashie.R.drawable.ic_repeat_one
                else com.dashieapp.Dashie.R.drawable.ic_repeat
            )
            MusicPlayerStyles.applyToggleButtonState(it, active, forceDarkMode = true)
        }
    }

    fun setPlaylistButtonActive(active: Boolean) {
        playlistButton?.setColorFilter(
            if (active) MusicPlayerStyles.ACCENT_COLOR else MusicPlayerStyles.TEXT_SECONDARY_DARK
        )
    }

    fun setSpeakerButtonActive(active: Boolean) {
        speakerButton?.setColorFilter(
            if (active) MusicPlayerStyles.ACCENT_COLOR else MusicPlayerStyles.TEXT_SECONDARY_DARK
        )
    }

    fun getAlbumArtView(): ImageView? = albumArt

    fun getClickableButtons(): List<ImageButton> = listOfNotNull(
        playPauseButton, prevButton, nextButton, shuffleButton, repeatButton,
        playlistButton, speakerButton
    )

    fun setGroupActive(active: Boolean, speakerCount: Int = 0) {
        hasActiveGroup = active
        val toggleColumn = (volumeToggleButton?.parent as? View)
        if (active) {
            toggleColumn?.visibility = View.VISIBLE
            updateToggleIcon()
        } else {
            toggleColumn?.visibility = View.GONE
        }
        updateSpeakerGroupIndicator(active, speakerCount)
    }

    /** Update speaker icon (group vs single) and count badge. */
    private fun updateSpeakerGroupIndicator(isGroup: Boolean, count: Int) {
        speakerIcon?.setImageResource(
            if (isGroup) com.dashieapp.Dashie.R.drawable.ic_speaker_group
            else com.dashieapp.Dashie.R.drawable.ic_box_speaker
        )
        if (count > 1) {
            speakerCountBadge?.text = "$count"
            speakerCountBadge?.visibility = View.VISIBLE
        } else {
            speakerCountBadge?.visibility = View.GONE
        }
    }

    private fun toggleVolumeMode() {
        val coord = volumeCoordinator ?: return
        if (!coord.isGroupActive) return
        coord.toggleVolumeMode()
        updateToggleIcon()
    }

    /** Update the toggle icon and label: group speaker when controlling group, single speaker for individual. */
    private fun updateToggleIcon() {
        volumeToggleButton?.setImageResource(
            if (isGroupVolumeMode) com.dashieapp.Dashie.R.drawable.ic_speaker_group
            else com.dashieapp.Dashie.R.drawable.ic_box_speaker
        )
        val deviceName = volumeCoordinator?.thisDeviceSpeakerName?.takeIf { it.isNotEmpty() }
            ?: android.os.Build.MODEL
        volumeToggleLabel?.text = if (isGroupVolumeMode) "Group" else deviceName
    }

    fun destroy() {
        volumeBar?.destroy()
    }
}

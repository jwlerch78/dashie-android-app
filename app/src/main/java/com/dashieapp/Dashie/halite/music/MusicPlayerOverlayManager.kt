package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelCoordinator
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelParticipant

/**
 * Manages the music player overlay card.
 * Handles showing/hiding the player based on media state.
 * Persists visibility across app restarts via [onSaveVisibility].
 * Polls for Music Assistant readiness when showing with no cached media.
 *
 * Uses Activity-level views (ViewGroup) instead of WindowManager overlays,
 * eliminating the need for SYSTEM_ALERT_WINDOW permission.
 *
 * Implements [ScreensaverPanelParticipant] to show the music player on the
 * screensaver overlay panel when the "Show with Screensaver" preference is enabled.
 */
class MusicPlayerOverlayManager(
    private val context: Context,
    private val overlayContainer: ViewGroup,
    private val onToggleMinimized: (() -> Unit)? = null,
    /** Visibility gate — registers the player card and the hidden bubble
     *  as OVERLAY_KIOSK_OR_FULL. Force-hides on transition to OFF mode
     *  (login screen / logout) but stays out of the way in FULL/KIOSK so
     *  music continues to play and the player can be reopened. */
    private val visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
) : ScreensaverPanelParticipant {
    companion object {
        private const val TAG = "MusicPlayerOverlayMgr"
        private const val MAX_POLL_ATTEMPTS = 60  // 5 minutes max
    }

    private var playerCard: MusicPlayerCard? = null
    private var currentData: MusicPlayerData = MusicPlayerData()
    /** Music profile manager for per-person music scoping (set externally before card creation) */
    var musicProfileManager: MusicProfileManager? = null
    private var isVisible = false
    private var userDismissed = false
    private var userExplicitShow = false
    private var lastDismissedTrack: String = ""

    // Polling state for Music Assistant readiness
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var pollAttempts = 0

    // Callbacks
    var onPlayPauseClicked: (() -> Unit)? = null
    var onNextClicked: (() -> Unit)? = null
    var onPreviousClicked: (() -> Unit)? = null
    var onStopClicked: (() -> Unit)? = null
    var onVolumeChangeClicked: ((Float) -> Unit)? = null
    var onShuffleClicked: (() -> Unit)? = null
    var onRepeatClicked: (() -> Unit)? = null
    var onSeekRequested: ((Long) -> Unit)? = null

    /** Called when visibility changes so the caller can persist to prefs. */
    var onSaveVisibility: ((shown: Boolean) -> Unit)? = null

    /** Called to re-inject the JS subscription (for polling). */
    var onRequestReInject: (() -> Unit)? = null

    /** Called to save drag position. Args: isMinimized, x, y */
    var onSavePosition: ((Boolean, Int, Int) -> Unit)? = null

    /** Called to load saved positions. Returns Pair(normalPos, miniPos) each as "x,y" or null. */
    var onLoadPositions: (() -> Pair<String?, String?>)? = null

    /** Called to cache track data for next startup. Args: track, artist, albumArtUrl */
    var onSaveTrackData: ((String, String, String?) -> Unit)? = null

    /** Called to load cached track data. Returns Triple(track, artist, albumArtUrl) or null. */
    var onLoadTrackData: (() -> Triple<String, String, String?>?)? = null

    /** Returns true when the MA session token is expired (401) — the player
     *  should show a "sign in" prompt instead of a perpetual "Reconnecting…". */
    var onIsSessionExpired: (() -> Boolean)? = null

    /** Fired to surface the (re-armable) MA re-login dialog when the player is
     *  shown in an expired-session state. */
    var onSessionExpiredSignIn: (() -> Unit)? = null

    /** Fired when player visibility actually changes. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /**
     * Fired when the player's bottom-edge inset changes (dp).
     * 0 = no inset (hidden, maximized, or floating normal mode).
     * >0 = the player occupies this many dp at the bottom edge.
     */
    var onBottomInsetDp: ((Int) -> Unit)? = null

    /** Called when the active entity switches (for updating recently used list). */
    var onEntitySwitched: ((entityId: String, friendlyName: String) -> Unit)? = null

    /** Called when user taps the speaker indicator to open entity picker. */
    var onSpeakerClicked: (() -> Unit)? = null

    /** Called when user taps a recently played item to replay it. */
    var onPlayRecentItemClicked: ((String) -> Unit)? = null

    /** Called to request a refresh of recently played data from JS. */
    var onRequestRecentlyPlayed: (() -> Unit)? = null

    /** Called when the speaker drawer is toggled open — fetch speaker list. */
    var onSpeakerDrawerToggle: (() -> Unit)? = null

    /** Called when user joins/unjoins a speaker from the group. */
    var onSpeakerJoin: ((playerId: String, join: Boolean) -> Unit)? = null

    /** Called when user changes a speaker's volume. */
    var onSpeakerVolumeChange: ((playerId: String, percent: Int) -> Unit)? = null

    /** Called when user mutes/unmutes a speaker. */
    var onSpeakerMuteToggle: ((playerId: String, muted: Boolean) -> Unit)? = null

    /** Called when user changes the group volume. */
    var onGroupVolumeChange: ((percent: Int) -> Unit)? = null

    /** Called when user toggles group mute. */
    var onGroupMuteToggle: ((muted: Boolean) -> Unit)? = null

    /** Coordinator for shared volume state. Passed to renderers and drawers. */
    var volumeCoordinator: MusicStateCoordinator? = null
        set(value) {
            field = value
            playerCard?.setVolumeCoordinator(value)
        }

    /** Called when user taps a speaker/group to transfer playback. */
    var onTransferQueue: ((targetPlayerId: String, targetName: String) -> Unit)? = null
    /** Called when user taps × to clear/stop a paused queue. */
    var onClearQueue: ((targetPlayerId: String) -> Unit)? = null

    /** Whether to open in full screen mode when playback starts. Set from prefs. */
    var fullScreenOnPlay: Boolean = false

    /** Whether to show on screensaver panel. Set from prefs. */
    var showWithScreensaver: Boolean = false

    /** Screensaver panel coordinator — set after init by HaliteComponentRegistry */
    var coordinator: ScreensaverPanelCoordinator? = null

    /** Whether the card is currently on the screensaver panel */
    private var cardOnPanel: Boolean = false

    /** Mode before screensaver activation (to restore when screensaver deactivates) */
    private var modeBeforeScreensaver: String? = null  // "normal", "minimized", "maximized", "strip"

    /** Hidden state: player is backgrounded to a floating music note bubble */
    private var isHidden = false
    private var modeBeforeHide: String? = null  // "strip", "minimized", "maximized", "normal"
    private var hiddenBubble: View? = null

    // ── ScreensaverPanelParticipant ─────────────────────────────────

    override val participantId: String = "music"
    override val participantPriority: Int = 1  // Below timers (0), above video feeds (2)

    override fun getScreensaverCardCount(): Int =
        if (isScreensaverCardLive()) 1 else 0

    override fun getScreensaverCards(): List<View> =
        if (isScreensaverCardLive()) listOf(playerCard!!) else emptyList()

    /**
     * A music card counts toward the screensaver panel only when the player is
     * genuinely displayed — mirroring the dashboard: the logical flags are set,
     * the card View is actually attached and visible, there is real media, AND
     * the player is not stopped/idle.
     *
     * The `!isStopped` check is the key. Paused, stopped and idle all leave
     * `hasMedia` true (the track name lingers), so an MA player reporting
     * state=idle while keeping the last track loaded would otherwise keep the
     * count at 1. The shared panel then stayed visible reserving the right strip
     * and stranded the screensaver photo with a blank panel (the reported bug:
     * "music player sidebar stuck with nothing displayed"). A genuinely paused
     * player (isStopped=false) still shows, matching the dashboard.
     */
    private fun isScreensaverCardLive(): Boolean {
        val card = playerCard ?: return false
        return cardOnPanel && isVisible &&
            card.parent != null && card.visibility == View.VISIBLE &&
            hasActiveMedia() && !currentData.isStopped
    }

    /** Tracks whether the panel currently counts this card, to fire add/remove only on transitions. */
    private var screensaverCardCounted = false

    /**
     * Keep the shared screensaver panel in sync with live playback while the
     * card sits on the panel. If the player stops/idles mid-screensaver the
     * panel hides (photo reclaims the strip); when playback resumes the panel
     * comes back. The card View stays a child of the panel throughout — only
     * the panel's visibility (driven by getScreensaverCardCount) flips. Without
     * this, a player that stops during a long screensaver session stranded a
     * blank strip until the next screensaver activation.
     */
    private fun syncScreensaverPanelLiveness() {
        val live = isScreensaverCardLive()
        if (live == screensaverCardCounted) return
        screensaverCardCounted = live
        if (live) coordinator?.notifyCardAdded() else coordinator?.notifyCardRemoved()
    }

    override fun onPanelItemCountChanged(totalItems: Int) {
        val isScreensaver = coordinator?.isScreensaverActive == true
        val widthDp = (context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density)
        val isNarrowPortrait = widthDp < 750f
        Log.d(TAG, "🎵 onPanelItemCountChanged: totalItems=$totalItems, cardOnPanel=$cardOnPanel, " +
            "isVisible=$isVisible, isScreensaver=$isScreensaver, narrowPortrait=$isNarrowPortrait, " +
            "mode=${when { currentData.isMaximized -> "maximized"; currentData.isMinimized -> "minimized"; else -> "normal" }}")
        if (!cardOnPanel || !isVisible) return
        // Narrow portrait screensaver: always use NORMAL (compact) mode regardless
        // of card count — the card docks in a 25%-height bottom band and
        // MAXIMIZED/MINIMIZED internal layouts don't fit that shape well.
        if (isScreensaver && isNarrowPortrait) {
            if (currentData.isMaximized || currentData.isMinimized) {
                Log.i(TAG, "🎵 Narrow portrait screensaver — forcing music to normal mode")
                setPanelMode(minimized = false, maximized = false)
            }
            return
        }
        when {
            totalItems >= 4 -> {
                if (!currentData.isMinimized) {
                    Log.i(TAG, "🎵 Panel has $totalItems items — minimizing music player")
                    setPanelMode(minimized = true, maximized = false)
                }
            }
            totalItems in 2..3 -> {
                if (currentData.isMaximized || currentData.isMinimized) {
                    Log.i(TAG, "🎵 Panel has $totalItems items — normalizing music player")
                    setPanelMode(minimized = false, maximized = false)
                }
            }
            totalItems <= 1 -> {
                if (isScreensaver && !currentData.isMaximized) {
                    Log.i(TAG, "🎵 Music alone on screensaver panel — auto-maximizing")
                    setPanelMode(minimized = false, maximized = true)
                } else if (!isScreensaver && (currentData.isMaximized || currentData.isMinimized)) {
                    Log.i(TAG, "🎵 Music alone on sidebar panel — normalizing")
                    setPanelMode(minimized = false, maximized = false)
                }
            }
        }
    }

    /**
     * Change player mode while on the panel.
     * Uses silent mode change + restyle to avoid touch handler position saves/restores
     * that would apply floating-mode positioning and displace the card from the panel.
     */
    private fun setPanelMode(minimized: Boolean, maximized: Boolean) {
        Log.i(TAG, "🎵 setPanelMode: minimized=$minimized, maximized=$maximized (was: " +
            "min=${currentData.isMinimized}, max=${currentData.isMaximized})")
        currentData = currentData.copy(isMinimized = minimized, isMaximized = maximized)
        playerCard?.setModeSilently(isMinimized = minimized, isMaximized = maximized)
        playerCard?.forceRestyle()
        // forceRestyle() → render() applies floating-mode layoutParams (gravity, margins).
        // Get fresh dimensions from cardLayoutParams, then reset positioning so
        // the coordinator can handle panel positioning.
        val card = playerCard ?: return
        val freshLp = card.cardLayoutParams
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = freshLp.width
        lp.height = freshLp.height
        lp.gravity = Gravity.NO_GRAVITY
        lp.leftMargin = 0
        lp.topMargin = 0
        lp.rightMargin = 0
        lp.bottomMargin = 0
        card.layoutParams = lp
        Log.i(TAG, "🎵 setPanelMode done: w=${lp.width}, h=${lp.height}")
        // Re-forward recently played data after restyle
        if (!minimized && !maximized) {
            recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
        }
        // Safety net: request relayout on next frame to ensure position update
        card.post { coordinator?.requestRelayout() }
    }

    // Recently played data cache
    private var recentlyPlayedData: RecentlyPlayedData? = null
    private var lastRecentlyPlayedFetch: Long = 0

    // Current entity tracking for dynamic entity switching
    private var currentEntityId: String = ""
    private var currentFriendlyName: String = ""

    /**
     * Show the music player with the given state.
     * Creates the card if it doesn't exist.
     */
    fun showPlayer(data: MusicPlayerData) {
        Log.i(TAG, "🎵 showPlayer: track=${data.trackName}, playing=${data.isPlaying}, " +
            "isVisible=$isVisible, userExplicitShow=$userExplicitShow, userDismissed=$userDismissed, " +
            "cardOnPanel=$cardOnPanel, isStrip=${data.isStrip}, isMin=${data.isMinimized}, " +
            "isMax=${data.isMaximized}, currentIsStrip=${currentData.isStrip}, " +
            "screensaverActive=${coordinator?.isScreensaverActive}")
        currentData = data

        if (playerCard == null) {
            createPlayerCard()
        }

        // When reopening (card exists but not visible), force the card to the requested mode.
        // The card preserves its own internal mode flags, so after close→reopen from
        // screensaver panel it may still think it's in maximized/panel mode.
        if (!isVisible) {
            if (data.isStrip) {
                playerCard?.setStrip()
            } else if (data.isMinimized) {
                playerCard?.setMinimized()
            } else if (data.isMaximized) {
                playerCard?.setMaximized()
            } else {
                playerCard?.setNormal()
            }
        }

        playerCard?.updateState(data)

        if (!isVisible) {
            try {
                playerCard?.let { card ->
                    overlayContainer.addView(card, card.cardLayoutParams)
                    isVisible = true
                    onSaveVisibility?.invoke(true)
                    onVisibilityChanged?.invoke(true)
                    notifyBottomInset()
                    Log.i(TAG, "🎵 Music player card added to overlay")

                    // Slide-up entrance for strip/minimized bar modes
                    if (data.isStrip || data.isMinimized) {
                        card.animateEntrance()
                    }

                    // If screensaver is active and showWithScreensaver is enabled,
                    // migrate to the panel — but NOT if the user explicitly opened the player
                    // (e.g., re-opening after dismissing on screensaver should stay as strip)
                    if (coordinator?.isScreensaverActive == true && showWithScreensaver && !userExplicitShow) {
                        Log.i(TAG, "🎵 Screensaver already active — migrating to panel instead of auto-maximize")
                        onScreensaverActivated()
                    } else if (fullScreenOnPlay && !data.isMinimized && !data.isMaximized) {
                        Log.i(TAG, "🎵 Full screen on play enabled — auto-maximizing")
                        maximizePlayer()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to add music player to overlay", e)
            }
        }

        // Forward cached data to the new card
        cachedMediaBrowserDataSource?.let { playerCard?.setMediaBrowserDataSource(it) }
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
        requestRecentlyPlayedIfStale()
    }

    /**
     * Update the recently played data from JS bridge.
     * Caches data and forwards to the card.
     */
    fun updateRecentlyPlayed(data: RecentlyPlayedData) {
        recentlyPlayedData = data
        playerCard?.updateRecentlyPlayed(data)
    }

    /** Whether recents data has been loaded (idle suggestion available). */
    fun hasRecentlyPlayedData(): Boolean = recentlyPlayedData?.items?.isNotEmpty() == true

    /** Set the data source for the media browser panel's library browsing. */
    private var cachedMediaBrowserDataSource: MediaBrowserDataSource? = null
    fun setMediaBrowserDataSource(ds: MediaBrowserDataSource) {
        cachedMediaBrowserDataSource = ds
        playerCard?.setMediaBrowserDataSource(ds)
    }

    /** Get the media browser data source (used by registry for categorized recents fetch). */
    fun getMediaBrowserDataSource(): MediaBrowserDataSource? = cachedMediaBrowserDataSource

    fun updateSpeakerDrawer(speakers: List<SpeakerGroupDrawer.SpeakerInfo>, groups: List<SpeakerGroupDrawer.GroupInfo> = emptyList(), thisDeviceId: String = "", currentTargetId: String = "", defaultEntityId: String = "") {
        playerCard?.updateSpeakerDrawer(speakers, groups, thisDeviceId, currentTargetId, defaultEntityId, volumeCoordinator)
    }

    fun updateSpeakerVolume(playerId: String, volumePercent: Int) {
        playerCard?.updateSpeakerVolume(playerId, volumePercent)
    }

    fun configureVisibilityStore(haBaseUrl: String, haAuthToken: String) {
        playerCard?.configureVisibilityStore(haBaseUrl, haAuthToken)
    }

    /** Update the current target in the speaker drawer (after transfer) and refresh speaker bar. */
    fun updateSpeakerDrawerCurrentTarget(targetPlayerId: String) {
        playerCard?.updateSpeakerDrawerCurrentTarget(targetPlayerId)
    }

    /** Returns display names of unavailable speakers in the given target group. */
    fun getOfflineGroupMembers(targetEntityId: String = ""): List<String> {
        return playerCard?.getOfflineGroupMembers(targetEntityId) ?: emptyList()
    }

    /** Find a speaker's display name by player ID (partial match). */
    fun findSpeakerName(playerId: String): String? {
        return playerCard?.findSpeakerName(playerId)
    }

    /**
     * Request a recently played fetch from JS, if data is stale (>30s old).
     */
    private fun requestRecentlyPlayedIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastRecentlyPlayedFetch > 30_000) {
            lastRecentlyPlayedFetch = now
            onRequestRecentlyPlayed?.invoke()
        }
    }

    /**
     * Hide the music player (respects userExplicitShow guard).
     */
    fun hidePlayer() {
        Log.i(TAG, "🎵 hidePlayer called: isVisible=$isVisible, userExplicitShow=$userExplicitShow, " +
            "userDismissed=$userDismissed, isHidden=$isHidden", Exception("hidePlayer stack trace"))

        if (isHidden) {
            Log.i(TAG, "🎵 hidePlayer: BLOCKED — player is in hidden/bubble state")
            return
        }

        // The userExplicitShow guard protects a user-opened player from
        // transient no-media blips on the dashboard. But when the card is on
        // the screensaver panel, leaving a stopped player there strands the
        // screensaver photo shrunk to one side — so a genuine stop must always
        // tear the card off the panel regardless of this flag. (Pause keeps
        // hasMedia=true and never reaches hidePlayer, so paused players still
        // stay on the panel as intended.)
        if (userExplicitShow && !cardOnPanel) {
            Log.i(TAG, "🎵 hidePlayer: BLOCKED by userExplicitShow flag")
            return
        }

        if (isVisible && playerCard != null) {
            try {
                removeCardFromCurrentParent()
                isVisible = false
                stopPolling()
                onSaveVisibility?.invoke(false)
                onVisibilityChanged?.invoke(false)
                notifyBottomInset()
                Log.i(TAG, "🎵 Music player card removed from overlay")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to remove music player from overlay", e)
            }
        }
    }

    /**
     * User-initiated hide from sidebar toggle.
     * Clears the userExplicitShow guard and force-hides the player.
     */
    fun userHidePlayer() {
        Log.i(TAG, "🎵 userHidePlayer: clearing userExplicitShow and force-hiding with animation")
        userExplicitShow = false
        // Animate slide-down for strip/minimized modes before hiding
        playerCard?.animateSlideDownAndClose {
            forceHidePlayer()
        } ?: forceHidePlayer()
    }

    /**
     * Timeout-triggered hide: clears userExplicitShow guard and force-hides.
     * Used by polling timeout and entity-not-found errors.
     */
    fun timeoutHidePlayer() {
        Log.i(TAG, "🎵 timeoutHidePlayer: clearing userExplicitShow and force-hiding")
        userExplicitShow = false
        forceHidePlayer()
    }

    /**
     * Force-hide the player, bypassing the userExplicitShow guard.
     * Used only for explicit user actions (stop button, disable toggle).
     */
    private fun forceHidePlayer() {
        Log.i(TAG, "🎵 forceHidePlayer: bypassing guard, cardOnPanel=$cardOnPanel, " +
            "isStrip=${currentData.isStrip}, isMin=${currentData.isMinimized}, isMax=${currentData.isMaximized}")
        if (isVisible && playerCard != null) {
            try {
                removeCardFromCurrentParent()
                // Reset mode flags so next open starts in strip mode
                currentData = currentData.copy(isMinimized = false, isMaximized = false, isStrip = false)
                isVisible = false
                stopPolling()
                onSaveVisibility?.invoke(false)
                onVisibilityChanged?.invoke(false)
                notifyBottomInset()
                Log.i(TAG, "🎵 Music player card force-removed from overlay")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to force-remove music player from overlay", e)
            }
        }
    }

    /** Remove card from whichever container it's in, notifying coordinator if on panel. */
    private fun removeCardFromCurrentParent() {
        val wasOnPanel = cardOnPanel
        (playerCard?.parent as? ViewGroup)?.removeView(playerCard)
        if (wasOnPanel) {
            cardOnPanel = false
            screensaverCardCounted = false
            modeBeforeScreensaver = null
            MusicPlayerStyles.forceDarkMode = false
            coordinator?.notifyCardRemoved()
        }
    }

    /**
     * Update the player state without changing visibility.
     */
    fun updateState(data: MusicPlayerData) {
        Log.d(TAG, "🎵 updateState: hasMedia=${data.hasMedia}, track=${data.trackName}, " +
            "playing=${data.isPlaying}, entity=${data.entityId}, isVisible=$isVisible, " +
            "userExplicitShow=$userExplicitShow, userDismissed=$userDismissed, isHidden=$isHidden")

        // When hidden, just update currentData and manage bubble visibility
        if (isHidden) {
            currentData = data.copy(isMinimized = currentData.isMinimized,
                isMaximized = currentData.isMaximized, isStrip = currentData.isStrip)
            if (!data.hasMedia && data.entityId.isEmpty()) {
                // Music stopped entirely — remove bubble and clear hidden state
                Log.i(TAG, "🎵 updateState: music stopped while hidden — removing bubble")
                hideHiddenBubble()
                isHidden = false
                modeBeforeHide = null
            }
            return
        }
        // Preserve local mode flags — external state updates don't know about panel/strip/minimize
        currentData = if (cardOnPanel) {
            data.copy(isMinimized = currentData.isMinimized, isMaximized = currentData.isMaximized,
                isStrip = currentData.isStrip)
        } else {
            data.copy(isMinimized = currentData.isMinimized, isMaximized = currentData.isMaximized,
                isStrip = currentData.isStrip)
        }

        // Detect entity switch
        if (data.entityId.isNotEmpty() && data.entityId != currentEntityId) {
            Log.i(TAG, "🎵 Entity switched: $currentEntityId -> ${data.entityId} (${data.friendlyName})")
            currentEntityId = data.entityId
            currentFriendlyName = data.friendlyName
            onEntitySwitched?.invoke(data.entityId, data.friendlyName)
        }

        // While docked on the screensaver panel, reflect play/stop transitions
        // on the shared panel immediately (hide when stopped, show when resumed).
        if (cardOnPanel && coordinator?.isScreensaverActive == true) {
            syncScreensaverPanelLiveness()
        }

        if (data.hasMedia) {
            // Real media arrived — stop polling and cache track data
            if (pollRunnable != null) {
                Log.i(TAG, "🎵 updateState: real media arrived, stopping poll")
                stopPolling()
            }
            if (data.trackName.isNotBlank() && data.trackName != "Waiting for Music Assistant...") {
                onSaveTrackData?.invoke(data.trackName, data.artistName, data.albumArtUrl)
            }
            // Clear dismissed flag when a different track starts (new playback session)
            if (userDismissed && data.trackName.isNotBlank() && data.trackName != lastDismissedTrack) {
                Log.i(TAG, "🎵 updateState: new track '${data.trackName}' differs from dismissed " +
                    "'$lastDismissedTrack' — clearing userDismissed")
                userDismissed = false
                lastDismissedTrack = ""
            }
            if (!isVisible) {
                if (data.isPlaying && !userDismissed) {
                    // Auto-show in minimized mode when music starts playing
                    Log.i(TAG, "🎵 updateState: music playing, auto-showing minimized player")
                    showPlayer(data.copy(isMinimized = true, isMaximized = false, isStrip = false))
                } else {
                    Log.d(TAG, "🎵 updateState: not visible, caching data silently")
                }
                return
            } else {
                playerCard?.updateState(data)
            }
        } else if (data.entityId.isNotEmpty()) {
            // Entity is connected but idle (no media playing).
            // Stop reconnecting and show idle state with recently played.
            if (pollRunnable != null) {
                Log.i(TAG, "🎵 updateState: entity connected (idle), stopping poll")
                stopPolling()
            }
            if (isVisible) {
                Log.d(TAG, "🎵 updateState: connected idle — updating card with idle state")
                playerCard?.updateState(data.copy(isReconnecting = false))
            }
        } else {
            if (isVisible) {
                Log.d(TAG, "🎵 updateState: no media, calling hidePlayer (guard is inside)")
                hidePlayer()
            }
            if (userDismissed) {
                Log.d(TAG, "🎵 updateState: clearing dismissed flag (no media)")
                userDismissed = false
            }
        }
    }

    fun minimizePlayer() {
        currentData = currentData.copy(isMinimized = true, isMaximized = false, isStrip = false)
        playerCard?.setMinimized()
        onToggleMinimized?.invoke()
        notifyBottomInset()
        Log.d(TAG, "🎵 Minimized player")
    }

    fun maximizePlayer() {
        currentData = currentData.copy(isMinimized = false, isMaximized = true, isStrip = false)
        playerCard?.setMaximized()
        notifyBottomInset()
        Log.d(TAG, "🎵 Maximized player")
    }

    fun normalizePlayer() {
        currentData = currentData.copy(isMinimized = false, isMaximized = false, isStrip = false)
        playerCard?.setNormal()
        onToggleMinimized?.invoke()
        notifyBottomInset()
        // Re-forward cached recently played data — setNormal() creates a fresh renderer
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
        Log.d(TAG, "🎵 Returned to normal player")
    }

    fun stripPlayer() {
        currentData = currentData.copy(isMinimized = false, isMaximized = false, isStrip = true)
        playerCard?.setStrip()
        notifyBottomInset()
        // Re-forward cached recently played data — setStrip() creates a fresh renderer
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
        Log.d(TAG, "🎵 Switched to strip player")
    }

    // ── Hidden State (background music note bubble) ─────────────────

    /**
     * Hide the player to background: animate out, show floating music note bubble.
     * The bubble appears in the top-right corner and tapping it restores the player.
     */
    fun hideToBackground() {
        if (isHidden || !isVisible) return
        modeBeforeHide = when {
            currentData.isStrip -> "strip"
            currentData.isMinimized -> "minimized"
            currentData.isMaximized -> "maximized"
            else -> "normal"
        }
        Log.i(TAG, "🎵 hideToBackground: mode=$modeBeforeHide")
        isHidden = true
        notifyBottomInset()

        // Animate out, then remove card and show bubble
        playerCard?.animateSlideDownAndClose {
            removeCardFromCurrentParent()
            isVisible = false
            showHiddenBubble()
        } ?: run {
            removeCardFromCurrentParent()
            isVisible = false
            showHiddenBubble()
        }
    }

    /**
     * Restore from hidden state: remove bubble, show player in previous mode.
     */
    fun restoreFromBackground() {
        if (!isHidden) return
        Log.i(TAG, "🎵 restoreFromBackground: restoring to mode=$modeBeforeHide")
        hideHiddenBubble()
        isHidden = false

        val mode = modeBeforeHide ?: "strip"
        modeBeforeHide = null

        val isMin = mode == "minimized"
        val isMax = mode == "maximized"
        val isStrp = mode == "strip" || mode == "normal"
        currentData = currentData.copy(isMinimized = isMin, isMaximized = isMax, isStrip = isStrp)

        userExplicitShow = true
        showPlayer(currentData)
    }

    /** Show the floating music note bubble in top-right corner. */
    private fun showHiddenBubble() {
        if (hiddenBubble != null) return
        val dp = { dpVal: Int -> MusicPlayerStyles.dpToPx(context, dpVal) }
        val size = dp(MusicPlayerStyles.HIDDEN_BUBBLE_SIZE_DP)
        val margin = dp(MusicPlayerStyles.HIDDEN_BUBBLE_MARGIN_DP)

        val bubble = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
                setStroke(dp(1), 0x66FFFFFF.toInt())
            }
            alpha = 0.6f
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 1.0f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 0.6f
                }
                false
            }
            setOnClickListener {
                Log.i(TAG, "🎵 Hidden bubble tapped — restoring player")
                restoreFromBackground()
            }
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_music_note)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = dp(12)
            setPadding(padding, padding, padding, padding)
        }
        bubble.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = margin
            rightMargin = margin
        }
        overlayContainer.addView(bubble, lp)
        visibilityGate?.register(
            bubble,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )
        hiddenBubble = bubble
        Log.i(TAG, "🎵 Hidden bubble shown")
    }

    /** Remove the floating music note bubble. */
    private fun hideHiddenBubble() {
        hiddenBubble?.let {
            visibilityGate?.unregister(it)
            (it.parent as? ViewGroup)?.removeView(it)
            Log.i(TAG, "🎵 Hidden bubble removed")
        }
        hiddenBubble = null
    }

    /** Whether the player is in hidden/background state. */
    fun isPlayerHidden(): Boolean = isHidden

    fun clearDismissed() {
        Log.d(TAG, "🎵 clearDismissed() called")
        userDismissed = false
    }

    // ── Screensaver Panel Migration ─────────────────────────────────

    /**
     * Called when screensaver activates.
     * If music is playing and "Show with Screensaver" is enabled, migrate card to panel.
     */
    fun onScreensaverActivated() {
        Log.i(TAG, "🎵 onScreensaverActivated: showWithScreensaver=$showWithScreensaver, " +
            "isVisible=$isVisible, isPlaying=${currentData.isPlaying}, cardOnPanel=$cardOnPanel, " +
            "playerCard=${playerCard != null}, coordinator=${coordinator != null}")
        if (!showWithScreensaver || !isVisible || playerCard == null) return
        // Don't migrate a player that isn't a genuinely-displayed real player:
        // stopped/idle (isStopped) OR a reconnecting/"Waiting for Music
        // Assistant…" placeholder (!hasActiveMedia, e.g. MA unreachable). Either
        // would dock as a blank strip and strand the screensaver photo.
        if (currentData.isStopped || !hasActiveMedia()) {
            Log.i(TAG, "🎵 Screensaver activated — player not live (stopped/reconnecting), not migrating")
            return
        }
        val panel = coordinator?.getPanel() ?: return

        if (cardOnPanel) {
            // Already on panel from sidebar mode — just update mode tracking
            // modeBeforeScreensaver was already saved during onSidebarActivated
            Log.i(TAG, "🎵 Screensaver activated — card already on panel from sidebar, keeping in place")
            screensaverCardCounted = isScreensaverCardLive()
            return
        }

        Log.i(TAG, "🎵 Screensaver activated — migrating music player to panel (mode=${
            when { currentData.isMaximized -> "maximized"; currentData.isMinimized -> "minimized"; else -> "normal" }
        })")
        cardOnPanel = true
        modeBeforeScreensaver = when {
            currentData.isStrip -> "strip"
            currentData.isMaximized -> "maximized"
            currentData.isMinimized -> "minimized"
            else -> "normal"
        }

        // Save drag position before migration so it can be restored on exit
        playerCard?.savePositionBeforePanelMigration()

        // Force dark mode for the black screensaver panel background
        MusicPlayerStyles.forceDarkMode = true

        // Remove from overlay
        (playerCard?.parent as? ViewGroup)?.removeView(playerCard)

        // Normalize before adding to panel — onPanelItemCountChanged will set the
        // correct mode (maximize if alone, normalize if with PIPs, minimize if 4+).
        // This avoids adding with MATCH_PARENT dimensions which confuses relayout.
        currentData = currentData.copy(isMinimized = false, isMaximized = false, isStrip = false)
        playerCard?.setModeSilently(isMinimized = false, isMaximized = false)
        playerCard?.forceRestyle()

        // Add to panel with normal-size layout params
        val lp = playerCard?.cardLayoutParams ?: return
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        panel.addView(playerCard, lp)
        screensaverCardCounted = isScreensaverCardLive()
        coordinator?.notifyCardAdded()
        notifyBottomInset()

        // Re-forward cached recently played data to the re-rendered card
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
    }

    /**
     * Called when screensaver deactivates.
     * Card stays on panel if still visible (coordinator handles background removal).
     */
    fun onScreensaverDeactivated() {
        Log.i(TAG, "🎵 onScreensaverDeactivated: cardOnPanel=$cardOnPanel, isVisible=$isVisible, " +
            "playerCard=${playerCard != null}, modeBeforeScreensaver=$modeBeforeScreensaver, " +
            "sidebarActive=${coordinator?.isSidebarActive}")
        if (!cardOnPanel || !isVisible || playerCard == null) return
        // Always restore music player to free-floating — outside screensaver,
        // music doesn't participate in the sidebar (that's just for video feeds)
        Log.i(TAG, "🎵 Screensaver deactivated — migrating music player back to overlayContainer (restoring to $modeBeforeScreensaver)")

        // Restore system dark mode detection
        MusicPlayerStyles.forceDarkMode = false

        (playerCard?.parent as? ViewGroup)?.removeView(playerCard)

        // Restore pre-screensaver mode before re-rendering (default to strip if unknown)
        val mode = modeBeforeScreensaver ?: "strip"
        val isMin = mode == "minimized"
        val isMax = mode == "maximized"
        val isStrp = mode == "strip" || mode == "normal"  // normal → strip (strip is the new default)
        currentData = currentData.copy(isMinimized = isMin, isMaximized = isMax, isStrip = isStrp)
        playerCard?.setModeSilently(isMinimized = isMin, isMaximized = isMax, isStrip = isStrp)
        modeBeforeScreensaver = null

        // Re-render with system colors and restored mode
        playerCard?.forceRestyle()

        val lp = playerCard?.cardLayoutParams ?: return
        overlayContainer.addView(playerCard, lp)
        cardOnPanel = false
        coordinator?.notifyCardRemoved()
        notifyBottomInset()

        // Restore drag position from before screensaver
        playerCard?.restorePositionAfterPanelMigration()

        // Re-forward cached recently played data to the re-rendered card
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
    }

    // ── Sidebar Panel Migration ────────────────────────────────────

    override fun onSidebarActivated() {
        if (!isVisible || playerCard == null) return
        // Only migrate to sidebar panel during screensaver — outside screensaver,
        // the sidebar is just for video feeds and the music player stays free-floating
        if (coordinator?.isScreensaverActive != true) {
            Log.i(TAG, "🎵 Sidebar activated outside screensaver — keeping music player free-floating")
            return
        }
        // If card is already on the panel (e.g. transitioning from screensaver to sidebar),
        // just notify the coordinator — don't try to add it again
        if (cardOnPanel) {
            Log.i(TAG, "🎵 Sidebar activated — card already on panel, skipping migration")
            return
        }
        val panel = coordinator?.getPanel() ?: return

        Log.i(TAG, "🎵 Sidebar activated — migrating music player to panel")
        cardOnPanel = true
        modeBeforeScreensaver = when {
            currentData.isStrip -> "strip"
            currentData.isMaximized -> "maximized"
            currentData.isMinimized -> "minimized"
            else -> "normal"
        }

        playerCard?.savePositionBeforePanelMigration()
        MusicPlayerStyles.forceDarkMode = true
        overlayContainer.removeView(playerCard)

        // Normalize before adding to panel — onPanelItemCountChanged will set correct mode
        currentData = currentData.copy(isMinimized = false, isMaximized = false, isStrip = false)
        playerCard?.setModeSilently(isMinimized = false, isMaximized = false)
        playerCard?.forceRestyle()

        val lp = playerCard?.cardLayoutParams ?: return
        lp.gravity = Gravity.NO_GRAVITY
        lp.setMargins(0, 0, 0, 0)
        panel.addView(playerCard, lp)
        coordinator?.notifyCardAdded()
        notifyBottomInset()

        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
    }

    override fun onSidebarDeactivated() {
        if (!cardOnPanel || !isVisible || playerCard == null) return
        // During screensaver, card should stay on the panel — screensaver manages it
        if (coordinator?.isScreensaverActive == true) {
            Log.i(TAG, "🎵 Sidebar deactivated but screensaver active — keeping card on panel")
            return
        }
        Log.i(TAG, "🎵 Sidebar deactivated — migrating music player back to overlayContainer")

        MusicPlayerStyles.forceDarkMode = false
        (playerCard?.parent as? ViewGroup)?.removeView(playerCard)

        // Restore pre-sidebar mode (default to strip if unknown)
        val mode = modeBeforeScreensaver ?: "strip"
        val isMin = mode == "minimized"
        val isMax = mode == "maximized"
        val isStrp = mode == "strip" || mode == "normal"
        currentData = currentData.copy(isMinimized = isMin, isMaximized = isMax, isStrip = isStrp)
        playerCard?.setModeSilently(isMinimized = isMin, isMaximized = isMax, isStrip = isStrp)
        modeBeforeScreensaver = null

        playerCard?.forceRestyle()
        val lp = playerCard?.cardLayoutParams ?: return
        overlayContainer.addView(playerCard, lp)
        cardOnPanel = false
        coordinator?.notifyCardRemoved()
        notifyBottomInset()

        playerCard?.restorePositionAfterPanelMigration()
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
    }

    /**
     * Show the player with the last known media data, or cached track from prefs.
     * Falls back to a placeholder if nothing is cached.
     * Starts polling for Music Assistant readiness if no live media.
     */
    fun showWithLastKnownState() {
        userExplicitShow = true
        Log.i(TAG, "🎵 showWithLastKnownState: hasMedia=${currentData.hasMedia}, " +
            "isVisible=$isVisible, cardOnPanel=$cardOnPanel, isHidden=$isHidden, currentMode=${when {
                currentData.isStrip -> "strip"; currentData.isMinimized -> "minimized";
                currentData.isMaximized -> "maximized"; else -> "normal"
            }}")
        // If player is in hidden/bubble state, restore it
        if (isHidden) {
            restoreFromBackground()
            return
        }
        if (currentData.hasMedia) {
            Log.i(TAG, "🎵 showWithLastKnownState: live track=${currentData.trackName}")
            showPlayer(currentData.copy(isPlaying = false, isMinimized = false, isMaximized = false, isStrip = true))
        } else if (currentData.entityId.isNotEmpty()) {
            // Sendspin already connected and idle — show idle state with recently played
            Log.i(TAG, "🎵 showWithLastKnownState: entity connected (idle) — ${currentData.entityId}")
            showPlayer(currentData.copy(isReconnecting = false, isMinimized = false, isMaximized = false, isStrip = true))
        } else if (onIsSessionExpired?.invoke() == true) {
            // MA session is expired — polling can never resolve this. Show the
            // sign-in prompt instead of a perpetual "Reconnecting…" spinner.
            Log.i(TAG, "🎵 showWithLastKnownState: MA session expired — showing sign-in prompt")
            showSessionExpiredState()
        } else {
            // Try loading cached track from previous session
            val cached = onLoadTrackData?.invoke()
            if (cached != null && cached.first.isNotBlank()) {
                Log.i(TAG, "🎵 showWithLastKnownState: cached track=${cached.first}")
                showPlayer(MusicPlayerData(
                    trackName = cached.first,
                    artistName = "Reconnecting...",
                    albumArtUrl = cached.third,
                    isPlaying = false,
                    isReconnecting = true,
                    isStrip = true
                ))
            } else {
                Log.i(TAG, "🎵 showWithLastKnownState: no cached data - showing placeholder")
                showPlayer(MusicPlayerData(
                    trackName = "Waiting for Music Assistant...",
                    artistName = "",
                    isPlaying = false,
                    isReconnecting = true,
                    isStrip = true
                ))
            }
            startPolling()
        }
    }

    /**
     * Show the expired-session placeholder and surface the (re-armable) MA
     * re-login dialog. Stops any polling — an expired token can't be recovered
     * by re-injecting. Fix B2 for the MA re-login trap: keeps the playback
     * overlay in sync with the real auth state instead of a false "Reconnecting".
     */
    private fun showSessionExpiredState() {
        stopPolling()
        showPlayer(MusicPlayerData(
            trackName = "Music Assistant",
            artistName = "Session expired — sign in",
            isPlaying = false,
            isReconnecting = false,
            isSessionExpired = true,
            isStrip = true
        ))
        // Surface the re-login dialog. showMaLoginIfExpired is guarded + re-armable
        // (Fix A), so this shows once and re-arms on dismiss — no spam loop.
        onSessionExpiredSignIn?.invoke()
    }

    fun isPlayerVisible(): Boolean = isVisible
    fun isMinimized(): Boolean = currentData.isMinimized
    fun isMaximized(): Boolean = currentData.isMaximized
    fun isCurrentlyPlaying(): Boolean = currentData.isPlaying
    fun hasActiveMedia(): Boolean = currentData.hasMedia && !currentData.isReconnecting
    fun getCurrentData(): MusicPlayerData = currentData

    /** Optimistically update UI to show playing state before HA state update arrives. */
    fun setOptimisticPlaying() {
        currentData = currentData.copy(isPlaying = true)
        playerCard?.updateState(currentData)
    }

    // ========== Polling for Music Assistant readiness ==========

    /**
     * Progressive polling interval: aggressive at first, then backs off.
     * Attempts 1-5: every 2s (first 10s - HA connection establishing)
     * Attempts 6-15: every 3s (next 30s - Music Assistant discovering)
     * Attempts 16+: every 8s (long tail - slow network/startup)
     */
    private fun getPollingInterval(): Long = when {
        pollAttempts <= 5 -> 2000L
        pollAttempts <= 15 -> 3000L
        else -> 8000L
    }

    private fun startPolling() {
        stopPolling()
        pollAttempts = 0
        pollRunnable = object : Runnable {
            override fun run() {
                pollAttempts++
                if (!isVisible || currentData.hasMedia) {
                    Log.d(TAG, "🎵 Poll stopped: visible=$isVisible, hasMedia=${currentData.hasMedia}")
                    stopPolling()
                    return
                }
                // Session expired mid-poll (a 401 arrived while we were waiting):
                // stop spinning "Reconnecting…" and switch to the sign-in prompt.
                if (onIsSessionExpired?.invoke() == true) {
                    Log.i(TAG, "🎵 Poll: MA session expired — switching to sign-in prompt")
                    showSessionExpiredState()
                    return
                }
                if (pollAttempts > MAX_POLL_ATTEMPTS) {
                    Log.i(TAG, "🎵 Polling timed out after $pollAttempts attempts")
                    stopPolling()
                    val errorData = MusicPlayerData(
                        trackName = "Music player not found",
                        artistName = "Check your configuration",
                        isPlaying = false,
                        isReconnecting = false
                    )
                    currentData = errorData
                    playerCard?.updateState(errorData)
                    handler.postDelayed({ timeoutHidePlayer() }, 8000L)
                    return
                }
                val interval = getPollingInterval()
                Log.d(TAG, "🎵 Poll attempt $pollAttempts/$MAX_POLL_ATTEMPTS - re-injecting (next in ${interval}ms)")
                onRequestReInject?.invoke()
                handler.postDelayed(this, interval)
            }
        }
        // First poll quickly — HA connection may already be up
        handler.postDelayed(pollRunnable!!, 2000L)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        pollAttempts = 0
    }

    // ========== Card creation ==========

    private fun createPlayerCard() {
        Log.d(TAG, "Creating music player card")
        playerCard = MusicPlayerCard(
            context = context,
            overlayContainer = overlayContainer,
            onPlayPause = {
                Log.d(TAG, "🎵 Play/Pause clicked")
                onPlayPauseClicked?.invoke()
            },
            onMinimize = { minimizePlayer() },
            onMaximize = { maximizePlayer() },
            onNormal = { normalizePlayer() },
            onNext = {
                Log.d(TAG, "🎵 Next clicked")
                onNextClicked?.invoke()
            },
            onPrevious = {
                Log.d(TAG, "🎵 Previous clicked")
                onPreviousClicked?.invoke()
            },
            onStop = {
                Log.d(TAG, "🎵 Stop clicked - clearing userExplicitShow, setting userDismissed")
                lastDismissedTrack = currentData.trackName
                userDismissed = true
                userExplicitShow = false
                // Animate out first, then stop + hide (avoids HA state update hiding before animation)
                playerCard?.animateSlideDownAndClose {
                    onStopClicked?.invoke()
                    forceHidePlayer()
                } ?: run {
                    onStopClicked?.invoke()
                    forceHidePlayer()
                }
            },
            onStrip = { stripPlayer() },
            onFloat = { normalizePlayer() },
            onVolumeChange = { level -> onVolumeChangeClicked?.invoke(level) },
            onPositionChanged = { x, y ->
                Log.d(TAG, "🎵 Position changed to ($x, $y)")
                val isMin = currentData.isMinimized
                onSavePosition?.invoke(isMin, x, y)
            },
            onSpeakerClicked = { onSpeakerClicked?.invoke() },
            onPlayRecentItem = { uri ->
                Log.i(TAG, "🎵 Play recently played item: $uri, callback=${onPlayRecentItemClicked != null}")
                onPlayRecentItemClicked?.invoke(uri)
            },
            onHide = { hideToBackground() },
            onSpeakerDrawerToggle = { onSpeakerDrawerToggle?.invoke() },
            onSpeakerJoin = { id, join -> onSpeakerJoin?.invoke(id, join) },
            onSpeakerVolumeChange = { id, pct -> onSpeakerVolumeChange?.invoke(id, pct) },
            onSpeakerMuteToggle = { id, muted -> onSpeakerMuteToggle?.invoke(id, muted) },
            onGroupVolumeChange = { pct -> onGroupVolumeChange?.invoke(pct) },
            onGroupMuteToggle = { muted -> onGroupMuteToggle?.invoke(muted) },
            onTransferQueue = { id, name -> onTransferQueue?.invoke(id, name) },
            onClearQueue = { id -> onClearQueue?.invoke(id) },
            onShuffleToggle = { onShuffleClicked?.invoke() },
            onRepeatCycle = { onRepeatClicked?.invoke() },
            onSeek = { positionMs -> onSeekRequested?.invoke(positionMs) }
        )
        // Register with the visibility gate — force-hides on OFF mode
        // (login screen / logout). Music keeps playing audio underneath
        // when the card is hidden; manager re-shows on its own logic
        // when the user reopens the player.
        playerCard?.let {
            visibilityGate?.register(
                it,
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
            )
        }

        // Pass volume coordinator to the new card
        volumeCoordinator?.let { playerCard?.setVolumeCoordinator(it) }

        // Notify bottom inset when strip height changes dynamically
        playerCard?.onStripHeightChangedDp = { _ -> notifyBottomInset() }

        // Load persisted positions into the touch handler
        loadSavedPositions()
    }

    private fun loadSavedPositions() {
        val (normalPos, miniPos) = onLoadPositions?.invoke() ?: return
        normalPos?.let { pos ->
            val parts = pos.split(",")
            if (parts.size == 2) {
                val x = parts[0].toIntOrNull()
                val y = parts[1].toIntOrNull()
                if (x != null && y != null) {
                    playerCard?.setSavedPosition(isMinimized = false, x = x, y = y)
                    Log.d(TAG, "🎵 Loaded saved normal position ($x, $y)")
                }
            }
        }
        miniPos?.let { pos ->
            val parts = pos.split(",")
            if (parts.size == 2) {
                val x = parts[0].toIntOrNull()
                val y = parts[1].toIntOrNull()
                if (x != null && y != null) {
                    playerCard?.setSavedPosition(isMinimized = true, x = x, y = y)
                    Log.d(TAG, "🎵 Loaded saved mini position ($x, $y)")
                }
            }
        }

        // Set music profile manager for per-person scoping
        playerCard?.musicProfileManager = musicProfileManager
    }

    /**
     * Forward configuration change to the music player card.
     * Called when system dark/light mode changes so the card can re-render.
     */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        playerCard?.dispatchConfigurationChanged(newConfig)
        // Re-forward cached recently played data after theme re-render
        recentlyPlayedData?.let { playerCard?.updateRecentlyPlayed(it) }
    }

    fun destroy() {
        Log.i(TAG, "Destroying music player overlay manager")
        stopPolling()
        hideHiddenBubble()
        isHidden = false
        userExplicitShow = false
        forceHidePlayer()
        playerCard?.let {
            visibilityGate?.unregister(it)
            it.destroy()
        }
        playerCard = null
    }

    // ── Bottom Inset ─────────────────────────────────────────────────

    /** Notify the inset listener based on current mode & visibility. */
    private fun notifyBottomInset() {
        // Only the mini player needs a bottom inset — the strip is a temporary
        // interaction surface, and maximized/normal/floating modes don't pin to bottom.
        val dp = if (!isVisible || isHidden || cardOnPanel) {
            0
        } else when {
            // Value is CSS px inside the HA iframe, not Android dp.
            // The mini bar is 56 native dp but ~20 CSS px in the iframe context.
            currentData.isMinimized -> 20
            else -> 0
        }
        Log.i(TAG, "🎵 notifyBottomInset: ${dp}dp (visible=$isVisible, hidden=$isHidden, " +
            "panel=$cardOnPanel, strip=${currentData.isStrip}, mini=${currentData.isMinimized}, " +
            "hasCallback=${onBottomInsetDp != null})")
        onBottomInsetDp?.invoke(dp)
    }
}

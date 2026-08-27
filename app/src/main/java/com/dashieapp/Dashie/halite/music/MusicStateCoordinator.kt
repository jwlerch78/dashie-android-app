package com.dashieapp.Dashie.halite.music

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Single owner of music player UI state.
 *
 * Receives inbound data from all sources (poller, Sendspin state, idle resolution)
 * via [MusicSourceCallbacks], merges into one [MusicPlayerData], and pushes to
 * [MusicPlayerOverlayManager].
 *
 * Position is interpolated from the poller's `elapsed_time` between polls for
 * smooth progress bar updates. Play/pause state comes from real-time Sendspin
 * callbacks for instant responsiveness.
 */
class MusicStateCoordinator(
    private val musicPlayer: MusicPlayerOverlayManager
) : MusicSourceCallbacks {

    companion object {
        private const val TAG = "MusicCoordinator"
    }

    // ── Active source ──

    private var activeSource: MusicSource? = null

    fun setActiveSource(source: MusicSource) {
        activeSource = source
        Log.i(TAG, "🎵 Active source: ${source.sourceId}")
    }

    // ── Target entity (set by fetchAndUpdateSpeakers group detection) ──

    @Volatile
    var targetEntity: String = ""
        private set

    /**
     * True when the user explicitly selected a target group via the speaker drawer.
     * Suppresses idle resolution from overriding the target. Clears on app restart
     * or when the user explicitly switches to a different group.
     */
    @Volatile
    var userSelectedTarget: Boolean = false
        private set

    // ── Position interpolation state ──

    @Volatile private var pollerPositionSec: Double = -1.0
    @Volatile private var pollerPositionAt: Long = 0  // SystemClock.elapsedRealtime()
    @Volatile private var pollerIsPlaying: Boolean = false

    // ── Current display state ──

    @Volatile private var currentTrack: String = ""
    @Volatile private var currentArtist: String = ""
    @Volatile private var currentAlbum: String = ""
    @Volatile private var currentArtworkUrl: String? = null
    @Volatile private var currentDuration: Int = 0
    @Volatile private var currentEntityId: String = ""
    @Volatile private var currentFriendlyName: String = ""
    @Volatile private var currentIsPlaying: Boolean = false
    /** True when MA reports the player stopped/idle (not playing, not paused). */
    @Volatile private var currentIsStopped: Boolean = false
    @Volatile private var currentVolume: Float = 0f
    @Volatile private var currentMuted: Boolean = false
    @Volatile private var currentShuffleEnabled: Boolean = false
    /** "off" | "all" | "one" — Music Assistant repeat modes. */
    @Volatile private var currentRepeatMode: String = "off"

    /**
     * Optimistic-update pin: when the user taps shuffle/repeat we apply the
     * new state locally and ignore poller-reported values for ~3s to avoid
     * the icon flickering back while MA is still processing the command.
     */
    @Volatile private var shuffleRepeatPinExpiresAt: Long = 0
    private val SHUFFLE_REPEAT_PIN_MS = 3000L
    private fun isShuffleRepeatPinned(): Boolean =
        shuffleRepeatPinExpiresAt > System.currentTimeMillis()

    fun pinShuffleState(enabled: Boolean) {
        currentShuffleEnabled = enabled
        shuffleRepeatPinExpiresAt = System.currentTimeMillis() + SHUFFLE_REPEAT_PIN_MS
    }

    fun pinRepeatMode(mode: String) {
        currentRepeatMode = mode
        shuffleRepeatPinExpiresAt = System.currentTimeMillis() + SHUFFLE_REPEAT_PIN_MS
    }

    fun getShuffleEnabled(): Boolean = currentShuffleEnabled
    fun getRepeatMode(): String = currentRepeatMode

    /** Now-playing snapshot for the AI `music` tool (MusicVoiceTool) — reads the state
     *  this coordinator already holds from the poller/Sendspin subscription (no extra
     *  connection). found=false when nothing is loaded or the player is stopped. */
    fun getNowPlayingSnapshot(): org.json.JSONObject {
        val found = currentTrack.isNotEmpty() && !currentIsStopped
        val o = org.json.JSONObject().put("found", found)
        if (!found) return o
        o.put("playing", currentIsPlaying)
        o.put("track", currentTrack)
        if (currentArtist.isNotEmpty()) o.put("artist", currentArtist)
        if (currentAlbum.isNotEmpty()) o.put("album", currentAlbum)
        if (currentFriendlyName.isNotEmpty()) o.put("speaker", currentFriendlyName)
        if (currentDuration > 0) {
            o.put("position_seconds", getInterpolatedPosition())
            o.put("duration_seconds", currentDuration)
        }
        return o
    }

    /**
     * Seek pin: after the user drags the scrubber we set the interpolation
     * baseline to the seeked-to position and stay pinned until MA's poller
     * reports a position close to that target (i.e., MA has actually applied
     * the seek). Without this, the next poll's stale pre-seek elapsed_time
     * would overwrite our baseline and the bar would jump back.
     *
     * The hard timeout is a safety net so we don't pin forever if MA never
     * reports a matching value (e.g., user pauses immediately after seeking).
     */
    @Volatile private var seekPinTargetSec: Int = -1
    @Volatile private var seekPinExpiresAt: Long = 0
    /**
     * After a local seek, trust local interpolation over poll-reported
     * positions until either the track changes or play-state transitions.
     * MA can leave elapsed_time stuck at the pre-seek value for the rest
     * of the track even though playback continues from the seeked-to spot,
     * so a time-based window isn't enough — we reject backward jumps until
     * a natural transition resets the truth.
     */
    @Volatile private var seekTrustActive: Boolean = false
    private val SEEK_PIN_TIMEOUT_MS = 8000L
    private val SEEK_PIN_TOLERANCE_SEC = 4
    private val MAX_BACKWARD_JUMP_SEC = 5
    private fun isSeekPinned(): Boolean =
        seekPinTargetSec >= 0 && seekPinExpiresAt > System.currentTimeMillis()

    fun pinPosition(positionSec: Int) {
        pollerPositionSec = positionSec.toDouble()
        pollerPositionAt = SystemClock.elapsedRealtime()
        seekPinTargetSec = positionSec
        seekPinExpiresAt = System.currentTimeMillis() + SEEK_PIN_TIMEOUT_MS
        seekTrustActive = true
    }

    // ── Volume state (single source of truth) ──

    /** Group volume 0-100 from MA group_volume. -1 = no group. */
    @Volatile var groupVolumePercent: Int = -1
    @Volatile var groupVolumeMuted: Boolean = false
    /** Individual speaker volume 0-100. */
    @Volatile var individualVolumePercent: Int = 50
    @Volatile var individualVolumeMuted: Boolean = false
    /** Whether a group is active (sync group or ad-hoc). */
    @Volatile var isGroupActive: Boolean = false
    /** Consecutive polls reporting group active — used for hysteresis. */
    @Volatile private var consecutiveGroupActivePolls: Int = 0
    /** Minimum consecutive active polls before reactivating group after deactivation. */
    private val GROUP_REACTIVATION_THRESHOLD = 2
    /** User's volume mode choice: true=group, false=individual. */
    @Volatile var isGroupVolumeMode: Boolean = true

    // ── Speaker group indicator state (shared across all renderers) ──

    /** Whether the current playback target is a group entity. */
    @Volatile var isCurrentTargetGroup: Boolean = false
    /** Number of speakers in the current group (0 if not a group). */
    @Volatile var speakerCount: Int = 0
    /** Friendly name of this device's speaker (for individual volume label). */
    @Volatile var thisDeviceSpeakerName: String = ""

    /** Cached group data for re-evaluation on target change. */
    @Volatile private var cachedGroups: List<SpeakerGroupDrawer.GroupInfo> = emptyList()

    /** Update speaker group indicator from speaker drawer data. */
    fun updateSpeakerGroupState(
        groups: List<SpeakerGroupDrawer.GroupInfo>,
        speakers: List<SpeakerGroupDrawer.SpeakerInfo>,
        currentTargetId: String,
        thisDeviceId: String = ""
    ) {
        // Only update cached groups if the new data has groups — don't lose
        // good data from an earlier fetch when a subsequent fetch returns empty
        if (groups.isNotEmpty()) cachedGroups = groups
        val effectiveTarget = currentTargetId.ifEmpty { targetEntity }
        val matchingGroup = cachedGroups.find { it.groupId == effectiveTarget }
        isCurrentTargetGroup = matchingGroup != null
        speakerCount = matchingGroup?.memberIds?.size ?: 0
        // Resolve this device's friendly speaker name
        if (thisDeviceId.isNotEmpty() && speakers.isNotEmpty()) {
            val thisSpk = speakers.find { it.playerId.contains(thisDeviceId) }
            if (thisSpk != null) thisDeviceSpeakerName = thisSpk.displayName
        }
        Log.i(TAG, "🔊 updateSpeakerGroupState: target='$effectiveTarget' isGroup=$isCurrentTargetGroup " +
            "count=$speakerCount deviceName='$thisDeviceSpeakerName'")
    }

    /** Re-evaluate group state when the target changes. */
    fun updateSpeakerGroupTarget(currentTargetId: String) {
        val matchingGroup = cachedGroups.find { it.groupId == currentTargetId }
        isCurrentTargetGroup = matchingGroup != null
        speakerCount = matchingGroup?.memberIds?.size ?: 0
        Log.d(TAG, "🔊 updateSpeakerGroupTarget: target='$currentTargetId' isGroup=$isCurrentTargetGroup count=$speakerCount")
    }

    /** Display scale 0-10 with rounding. Non-zero volume always shows at least 1. */
    fun volumeScale(percent: Int): Int {
        if (percent <= 0) return 0
        return ((percent / 10.0) + 0.5).toInt().coerceIn(1, 10)
    }

    /** The volume percent to display based on current mode. */
    fun getDisplayVolumePercent(): Int = if (isGroupVolumeMode && isGroupActive && groupVolumePercent >= 0)
        groupVolumePercent else individualVolumePercent

    /** The mute state to display based on current mode. Pinned value takes precedence during debounce. */
    fun getDisplayMuted(): Boolean {
        // If a local mute toggle is pending, return the pinned state to prevent oscillation
        if (pendingMuteExpiresAt > System.currentTimeMillis()) return pendingMuteState
        return if (isGroupVolumeMode && isGroupActive) groupVolumeMuted else individualVolumeMuted
    }

    /** The display value 0-10. */
    fun getDisplayVolumeScale(): Int {
        val muted = getDisplayMuted()
        return if (muted) 0 else volumeScale(getDisplayVolumePercent())
    }

    /** Pre-mute volume level (shared across all UI components). */
    var preMuteGroupPercent: Int = 50
    var preMuteIndividualPercent: Int = 50

    /** Get the pre-mute level for current mode. */
    fun getPreMutePercent(): Int = if (isGroupVolumeMode && isGroupActive)
        preMuteGroupPercent else preMuteIndividualPercent

    /** Save pre-mute level for current mode. */
    fun savePreMutePercent(percent: Int) {
        if (isGroupVolumeMode && isGroupActive) preMuteGroupPercent = percent.coerceAtLeast(10)
        else preMuteIndividualPercent = percent.coerceAtLeast(10)
    }

    /** Update group volume from speaker poll (fetchAndUpdateSpeakers). */
    fun updateGroupVolumeFromPoll(percent: Int, muted: Boolean, groupActive: Boolean) {
        val wasActive = isGroupActive
        val mutePinActive = pendingMuteExpiresAt > System.currentTimeMillis()
        // Don't overwrite volume/mute while a local mute toggle is pinned —
        // the poll may carry stale pre-mute values from MA's processing delay
        if (!mutePinActive) {
            groupVolumePercent = percent
            groupVolumeMuted = muted || percent == 0
        }
        // Hysteresis: deactivation is immediate, but reactivation requires
        // consecutive polls confirming group active. Prevents flip-flop from
        // stale syncedTo data or intermittent Sendspin visibility.
        if (groupActive) {
            consecutiveGroupActivePolls++
        } else {
            consecutiveGroupActivePolls = 0
        }
        val effectiveActive = if (wasActive) {
            groupActive  // Deactivation is immediate
        } else {
            consecutiveGroupActivePolls >= GROUP_REACTIVATION_THRESHOLD
        }
        isGroupActive = effectiveActive
        // Track last non-zero group volume as pre-mute restore level
        if (percent > 0 && !mutePinActive) preMuteGroupPercent = percent
        // Default to group mode when group first activates
        if (effectiveActive && !wasActive) isGroupVolumeMode = true
    }

    /** Update individual volume from poller or local change. */
    fun updateIndividualVolume(percent: Int, muted: Boolean) {
        val mutePinActive = pendingMuteExpiresAt > System.currentTimeMillis()
        if (!mutePinActive) {
            individualVolumePercent = percent
            individualVolumeMuted = muted || percent == 0
            // Track last non-zero volume as pre-mute restore level
            if (percent > 0) preMuteIndividualPercent = percent
        }
    }

    /** Toggle volume mode. Returns the new mode. */
    fun toggleVolumeMode(): Boolean {
        if (!isGroupActive) return false
        isGroupVolumeMode = !isGroupVolumeMode
        return isGroupVolumeMode
    }

    /** Called after a local volume/mute change. Prevents poll override for 2s. */
    @Volatile var lastVolumeChangeAt: Long = 0
        private set
    fun markLocalVolumeChange() { lastVolumeChangeAt = System.currentTimeMillis() }
    fun isVolumeDebounceActive(): Boolean = lastVolumeChangeAt > 0 && (System.currentTimeMillis() - lastVolumeChangeAt) < 2000

    /** Pending mute state — pins getDisplayMuted() for 3s after a local mute toggle.
     *  Prevents oscillation when MA reports volume_muted: false at volume 0 during transition. */
    @Volatile private var pendingMuteState: Boolean = false
    @Volatile private var pendingMuteExpiresAt: Long = 0
    fun pinMuteState(muted: Boolean) {
        pendingMuteState = muted
        pendingMuteExpiresAt = System.currentTimeMillis() + 3000
    }

    // ── Idle resolution state ──

    @Volatile private var idleTrack: String = ""
    @Volatile private var idleArtist: String = ""
    @Volatile private var idleArtworkUrl: String? = null
    @Volatile private var idleDuration: Int = 0
    @Volatile private var idleEntityId: String = ""
    @Volatile private var idleDisplayName: String = ""
    @Volatile private var hasIdleResolution: Boolean = false

    // ── UI tick for smooth position ──

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiTickRunnable = object : Runnable {
        override fun run() {
            if (currentIsPlaying && currentTrack.isNotEmpty()) {
                pushStateToUI()
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    fun start() {
        mainHandler.postDelayed(uiTickRunnable, 1000)
    }

    fun stop() {
        mainHandler.removeCallbacks(uiTickRunnable)
    }

    /**
     * User explicitly selected a target group in the speaker drawer.
     * Sets the target and locks it so idle resolution won't override.
     */
    fun setUserSelectedTarget(entityId: String, displayName: String = "") {
        Log.i(TAG, "🎵 User selected target: '$entityId' (was '$targetEntity') name='$displayName'")
        targetEntity = entityId
        userSelectedTarget = true
        // Clear current display state so the new group's data loads fresh
        currentTrack = ""
        currentArtist = ""
        currentAlbum = ""
        currentArtworkUrl = null
        currentDuration = 0
        currentEntityId = entityId
        currentFriendlyName = displayName
        currentIsPlaying = false
        currentIsStopped = true
        pollerPositionSec = -1.0
        pollerPositionAt = 0
        hasIdleResolution = false
        // Push immediately so UI shows the new group name
        pushStateToUI()
    }

    // ── Entity resolution (delegates to active source or uses own state) ──

    /** Entity for outbound commands — prefers source's resolution, falls back to targetEntity. */
    fun getCommandEntity(): String? {
        return activeSource?.getCommandEntity()
            ?: targetEntity.takeIf { it.isNotEmpty() }
    }

    /** Entity for poller queries — prefers source's resolution, falls back to targetEntity. */
    fun getPollerEntity(): String? {
        return activeSource?.getPollerEntity()
            ?: targetEntity.takeIf { it.isNotEmpty() }
    }

    // ── Position interpolation ──

    /** Get the current interpolated position in seconds. */
    fun getInterpolatedPosition(): Int {
        if (pollerPositionSec < 0 || pollerPositionAt == 0L) {
            return 0
        }
        return if (currentIsPlaying && pollerIsPlaying) {
            val elapsedMs = SystemClock.elapsedRealtime() - pollerPositionAt
            (pollerPositionSec + elapsedMs / 1000.0).toInt()
        } else {
            pollerPositionSec.toInt()
        }
    }

    // ── MusicSourceCallbacks implementation ──

    /**
     * Callback for when playback needs immediate entity resolution.
     * Set by HaliteComponentWiring to trigger fetchAndUpdateSpeakers.
     */
    var onNeedEntityResolution: (() -> Unit)? = null

    /** Timestamp when Sendspin detected playback start. Suppresses idle resolution briefly. */
    @Volatile private var playbackDetectedAt: Long = 0

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        // Poller is the sole authority for play state — don't set currentIsPlaying here.
        // But use Sendspin's signal to trigger entity resolution if we don't have
        // track metadata yet (Sendspin detects playback faster than the poller).
        if (isPlaying) {
            playbackDetectedAt = System.currentTimeMillis()
            if (currentTrack.isEmpty()) {
                Log.i(TAG, "🎵 Sendspin detected play with no track — requesting entity resolution")
                onNeedEntityResolution?.invoke()
            }
        }
    }

    /** True if Sendspin recently detected playback (within 5s). Suppresses idle resolution. */
    private fun isPlaybackTransition(): Boolean =
        playbackDetectedAt > 0 && (System.currentTimeMillis() - playbackDetectedAt) < 5000

    override fun onVolumeChanged(volume: Int, muted: Boolean) {
        if (!isVolumeDebounceActive()) {
            individualVolumePercent = volume
            individualVolumeMuted = muted
        }
        currentVolume = volume / 100f
        currentMuted = muted
        pushStateToUI()
    }

    override fun onPollerTrackUpdate(
        track: String, artist: String, album: String, artworkUrl: String?,
        positionSec: Double, durationSec: Int, isPlaying: Boolean,
        entityId: String, friendlyName: String,
        shuffleEnabled: Boolean,
        repeatMode: String,
        isStopped: Boolean
    ) {
        // Poller is the authority for all inbound state (Sendspin is outbound-only).

        // After a user-initiated group switch, reject stale data from the old entity.
        // The in-flight poll may return data for the previous target.
        if (userSelectedTarget && entityId.isNotEmpty() && entityId != targetEntity) {
            Log.d(TAG, "🎵 [POLLER] SKIP stale entity (user switched to '$targetEntity'): entity=$entityId")
            return
        }

        // Update position interpolation baseline.
        // positionSec = -1 means "no position data" (e.g., sync group) —
        // keep interpolating from the existing baseline. While seek-pinned,
        // only accept the poller's value once MA has caught up to within
        // tolerance of the seek target — otherwise stale pre-seek values
        // would bounce the bar back.
        if (positionSec >= 0) {
            // Compute what local interpolation expects right now — used to
            // detect MA's stuck-elapsed_time bug AND to advance our baseline
            // when we reject the bad value (so pause/resume freezes at the
            // right place instead of the seek target).
            val sinceLast = (SystemClock.elapsedRealtime() - pollerPositionAt) / 1000.0
            val expected = if (currentIsPlaying && pollerPositionAt > 0) {
                pollerPositionSec + sinceLast
            } else pollerPositionSec

            val accept = if (isSeekPinned()) {
                val target = seekPinTargetSec.toDouble()
                val caughtUp = kotlin.math.abs(positionSec - target) <= SEEK_PIN_TOLERANCE_SEC
                if (caughtUp) {
                    Log.d(TAG, "🎵 [SEEK] Pin released — MA caught up: target=$seekPinTargetSec reported=${positionSec.toInt()}")
                    seekPinTargetSec = -1
                } else {
                    Log.d(TAG, "🎵 [SEEK] Holding pin — target=$seekPinTargetSec reported=${positionSec.toInt()} (off by ${(positionSec - target).toInt()}s)")
                }
                caughtUp
            } else if (seekTrustActive && pollerPositionAt > 0) {
                // While seek-trust holds, reject backward jumps. MA may
                // leave elapsed_time stuck at the pre-seek value for the
                // rest of the track. Trust clears only on track change —
                // play-state transitions are unreliable signals (momentary
                // blips would prematurely accept a stuck value), and
                // distinguishing a real external backward seek from the
                // stuck-value bug isn't possible from poll deltas alone
                // (MaPollerService already adds local interpolation on top).
                //
                // Exception: same-track restart (repeat-one loop). When the
                // track name stays the same but playback has wrapped from
                // the end back to ~0, accept that as a legitimate reset.
                val isLoopRestart = positionSec < 5 &&
                    currentDuration > 0 &&
                    expected >= currentDuration - 5
                val backwardJump = expected - positionSec
                if (isLoopRestart) {
                    Log.i(TAG, "🎵 [SEEK] Accepting loop restart — was at ${expected.toInt()}/$currentDuration, now ${positionSec.toInt()}")
                    seekTrustActive = false
                    true
                } else if (backwardJump > MAX_BACKWARD_JUMP_SEC) {
                    Log.d(TAG, "🎵 [SEEK] Rejecting backward jump: reported=${positionSec.toInt()} expected=${expected.toInt()} (off by -${backwardJump.toInt()}s)")
                    false
                } else true
            } else true

            if (accept) {
                pollerPositionSec = positionSec
                pollerPositionAt = SystemClock.elapsedRealtime()
            } else if (currentIsPlaying) {
                // Rejected MA's value but advance baseline so pause freezes
                // at the right (locally-interpolated) position.
                pollerPositionSec = expected
                pollerPositionAt = SystemClock.elapsedRealtime()
            }
        } else if (isPlaying && pollerPositionAt == 0L) {
            // First poll with no position data — start from 0
            pollerPositionSec = 0.0
            pollerPositionAt = SystemClock.elapsedRealtime()
        }
        pollerIsPlaying = isPlaying

        // Track changed — reset position to 0 so interpolation starts fresh,
        // and clear any seek pin / trust so the new track flows through.
        if (track.isNotEmpty() && track != currentTrack && currentTrack.isNotEmpty()) {
            pollerPositionSec = 0.0
            pollerPositionAt = SystemClock.elapsedRealtime()
            seekPinTargetSec = -1
            seekTrustActive = false
        }

        // Update track metadata (only overwrite with non-empty values)
        if (track.isNotEmpty()) currentTrack = track
        if (artist.isNotEmpty()) currentArtist = artist
        if (album.isNotEmpty()) currentAlbum = album
        if (artworkUrl != null && artworkUrl.isNotEmpty()) currentArtworkUrl = artworkUrl
        if (durationSec > 0) currentDuration = durationSec
        if (entityId.isNotEmpty()) currentEntityId = entityId
        if (friendlyName.isNotEmpty()) currentFriendlyName = friendlyName

        // Poller is the authority for play state (Sendspin is outbound-only)
        currentIsPlaying = isPlaying
        currentIsStopped = isStopped

        // Shuffle/repeat: respect the optimistic pin so a freshly-tapped icon
        // doesn't flicker back when an in-flight poll returns the pre-tap value.
        if (!isShuffleRepeatPinned()) {
            currentShuffleEnabled = shuffleEnabled
            currentRepeatMode = repeatMode.ifEmpty { "off" }
        }

        Log.d(TAG, "🎵 [POLLER] track='${track.take(25)}' state=${if (isPlaying) "playing" else "idle"} pos=${positionSec.toInt()} dur=$durationSec entity=$entityId shuffle=$shuffleEnabled repeat=$repeatMode")

        pushStateToUI()
    }

    override fun onPollerIdle(entityId: String, friendlyName: String, volume: Float) {
        // Reject stale data from old entity after user-initiated switch
        if (userSelectedTarget && entityId.isNotEmpty() && entityId != targetEntity) {
            return
        }
        // Update individual volume from poller
        val volPct = (volume * 100).toInt()
        if (!isVolumeDebounceActive()) {
            individualVolumePercent = volPct
            individualVolumeMuted = volume < 0.01f
        }
        // Poller is the authority for idle state (Sendspin is outbound-only)
        currentIsStopped = true
        if (currentTrack.isNotEmpty()) {
            currentIsPlaying = false
            currentVolume = volume
            pushStateToUI()
        } else if (hasIdleResolution) {
            showIdleResolution()
        } else {
            // Truly empty — push minimal state
            currentIsPlaying = false
            currentVolume = volume
            if (entityId.isNotEmpty()) currentEntityId = entityId
            if (friendlyName.isNotEmpty()) currentFriendlyName = friendlyName
            pushStateToUI()
        }
    }

    override fun onIdleResolution(
        track: String, artist: String, artworkUrl: String?,
        durationSec: Int, entityId: String, displayName: String
    ) {
        // Always store idle resolution data (for speaker drawer, etc.)
        idleTrack = track
        idleArtist = artist
        idleArtworkUrl = artworkUrl
        idleDuration = durationSec
        idleEntityId = entityId
        idleDisplayName = displayName
        hasIdleResolution = true

        // Don't override UI when user has explicitly selected a different target
        if (userSelectedTarget && entityId != targetEntity) {
            Log.d(TAG, "🎵 [IDLE] Suppressed (user selected '$targetEntity'): track='${track.take(25)}' group='$displayName'")
            return
        }

        // Don't push idle resolution during playback transition (Sendspin detected play
        // but poller hasn't confirmed yet — idle data is stale)
        if (isPlaybackTransition()) {
            Log.d(TAG, "🎵 [IDLE] Suppressed (playback transition): track='${track.take(25)}' group='$displayName'")
            return
        }

        Log.i(TAG, "🎵 [IDLE] Resolved: track='${track.take(25)}' dur=$durationSec group='$displayName'")

        // Only show idle data if not currently playing AND no recents suggestion is loaded.
        // When a recents suggestion is showing, it matches the play button's URI —
        // overwriting it with idle-resolved data creates a display/action mismatch.
        if (!currentIsPlaying) {
            val hasRecentsSuggestion = musicPlayer.hasRecentlyPlayedData()
            if (!hasRecentsSuggestion) {
                val current = musicPlayer.getCurrentData()
                if (current.trackName.isEmpty() || !current.isPlaying) {
                    showIdleResolution()
                }
            }
        }
    }

    /**
     * Called when auto-detection finds a target entity (from IdleResolver).
     * @param entityId The detected entity
     * @param isPlaying True when this is a PLAYING group containing this device.
     *                  Playing groups always override userSelectedTarget because
     *                  active playback on this device takes priority.
     */
    override fun onTargetEntityChanged(entityId: String, isPlaying: Boolean) {
        // When user has explicitly selected a target, only allow changes that
        // match the target (e.g., the selected group starts playing).
        // Don't let OTHER playing groups override the user's choice — the old
        // group may still be "playing" while MA processes the stop command.
        if (userSelectedTarget) {
            if (entityId == targetEntity) {
                // The selected target was confirmed — clear the lock so auto-detection resumes
                Log.i(TAG, "🎵 Selected target confirmed (playing=$isPlaying): '$entityId'")
                userSelectedTarget = false
            } else if (isPlaying && !currentIsPlaying) {
                // A playing group containing this device was detected while our
                // selected target is idle. The user's switch completed and a new
                // playing context exists — clear the lock.
                Log.i(TAG, "🎵 Playing group detected while target idle — clearing userSelectedTarget: '$targetEntity' → '$entityId'")
                userSelectedTarget = false
            } else {
                Log.d(TAG, "🎵 Target entity change suppressed (user selected '$targetEntity'): '$entityId' playing=$isPlaying")
                return
            }
        }
        if (entityId != targetEntity) {
            // Always allow switching TO a playing entity (it has priority).
            // Only suppress switching to an idle entity when we're currently playing —
            // that's the idle resolver trying to override the active playback target.
            if (!isPlaying && currentIsPlaying) {
                Log.d(TAG, "🎵 Target entity change suppressed (currently playing on '$targetEntity'): '$entityId' is idle")
                return
            }
            Log.i(TAG, "🎵 Target entity: '$targetEntity' → '$entityId' (playing=$isPlaying)")
            targetEntity = entityId
            // Reset position interpolation — new entity, new baseline
            pollerPositionSec = 0.0
            pollerPositionAt = SystemClock.elapsedRealtime()
            // Re-evaluate speaker group state for the new target
            updateSpeakerGroupTarget(entityId)
        }
    }

    // ── Optimistic UI updates (called by MusicCommandRouter) ──

    /** Apply an optimistic state update immediately (e.g., toggle play on button press). */
    fun applyOptimisticUpdate(data: MusicPlayerData) {
        mainHandler.post {
            musicPlayer.updateState(data)
        }
    }

    // ── Internal ──

    private fun showIdleResolution() {
        currentTrack = idleTrack
        currentArtist = idleArtist
        currentArtworkUrl = idleArtworkUrl
        currentDuration = idleDuration
        currentEntityId = idleEntityId
        currentFriendlyName = idleDisplayName
        currentIsPlaying = false
        currentIsStopped = true

        Log.i(TAG, "🎵 [IDLE→UI] track='${idleTrack.take(25)}' dur=$idleDuration group='$idleDisplayName'")
        pushStateToUI()
    }

    private fun pushStateToUI() {
        val position = getInterpolatedPosition()
        val current = musicPlayer.getCurrentData()

        // Use targetEntity as fallback when currentEntityId hasn't been set yet.
        // This prevents the UI from showing "reconnecting" when we know what
        // entity we're targeting but haven't received track data yet.
        val effectiveEntityId = currentEntityId.ifEmpty { targetEntity }
        val effectiveFriendlyName = currentFriendlyName.ifEmpty {
            if (effectiveEntityId.isNotEmpty()) "Music Assistant" else ""
        }

        // Volume: use coordinator's display values (single source of truth)
        val displayVolPct = getDisplayVolumePercent()
        val displayMuted = getDisplayMuted()

        val data = MusicPlayerData(
            trackName = currentTrack,
            artistName = currentArtist,
            albumName = currentAlbum,
            albumArtUrl = currentArtworkUrl,
            isPlaying = currentIsPlaying,
            positionSeconds = position,
            durationSeconds = currentDuration,
            volumeLevel = displayVolPct / 100f,
            isVolumeMuted = displayMuted,
            isStopped = currentIsStopped,
            // Preserve UI layout state
            isMinimized = current.isMinimized,
            isMaximized = current.isMaximized,
            isStrip = current.isStrip,
            entityId = effectiveEntityId,
            friendlyName = effectiveFriendlyName,
            shuffleEnabled = currentShuffleEnabled,
            repeatMode = currentRepeatMode
        )

        mainHandler.post {
            musicPlayer.updateState(data)
        }
    }
}

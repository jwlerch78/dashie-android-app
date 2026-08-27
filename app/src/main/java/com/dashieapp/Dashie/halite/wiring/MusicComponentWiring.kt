package com.dashieapp.Dashie.halite.wiring

import com.dashieapp.Dashie.edition.ApiPaths

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.music.MaApiClient
import com.dashieapp.Dashie.halite.music.MaVoicePlay
import com.dashieapp.Dashie.halite.music.MaVoiceSearchResolver
import com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector

/**
 * Music player wiring extracted from HaliteComponentWiring.
 *
 * Owns all music-related properties, MA API client creation,
 * speaker polling, Sendspin lifecycle, and the giant
 * wireMusicPlayerCallbacks() method.
 */
object MusicComponentWiring {
    private const val TAG = "HaliteWiring"

    /** True when user has locally paused ExoPlayer. Suppresses JS isPlaying overrides. */
    @Volatile
    private var locallyPaused: Boolean = false

    /** Timestamp of last user volume change — for optimistic UI. */
    @Volatile
    private var lastVolumeChangeAt: Long = 0

    /** The volume level the user set — shown optimistically until server converges. */
    @Volatile
    private var userSetVolume: Float = -1f

    /** Consecutive polls where this device's Sendspin player was missing from MA. */
    @Volatile
    private var missingPlayerPollCount: Int = 0
    private const val MISSING_PLAYER_RECONNECT_THRESHOLD = 3

    /** Called from sidebar volume popout. Bridges Android device volume to music coordinator. */
    fun onSidebarVolumeChanged(volumeScale0to10: Int) {
        val coord = musicCoordinator ?: return
        val percent = (volumeScale0to10 * 10).coerceIn(0, 100)
        val muted = volumeScale0to10 == 0
        coord.markLocalVolumeChange()
        coord.updateIndividualVolume(percent, muted)
    }

    // Push metadata from MA provider's setMediaInfo — survives JS re-injection resets.
    // When JS subscription sends "Unknown Track" after re-injection, these fields take priority.
    @Volatile private var pushTrackName: String = ""
    @Volatile private var pushArtistName: String = ""
    @Volatile private var pushAlbumName: String = ""
    @Volatile private var pushAlbumArtUrl: String = ""
    @Volatile private var pushDurationSeconds: Int = 0
    @Volatile private var pushIsPlaying: Boolean = false

    /** True when MA API poller is actively providing state updates. Suppresses JS subscription data. */
    @Volatile private var maApiPollerActive: Boolean = false
    /** Timestamp of last successful MA API poll — used to detect staleness. */
    @Volatile private var lastMaApiPollAt: Long = 0
    /** Set to true after transfer to force immediate poll with position reset. */
    @Volatile private var forcePollerRefresh: Boolean = false
    /** Last poller data — used to merge duration and position into Sendspin pushes. */
    @Volatile private var lastPollerData: com.dashieapp.Dashie.halite.music.MusicPlayerData? = null
    /** Poller position tracking for Sendspin merge — elapsed_time from MA. */
    @Volatile private var lastPollerPositionSec: Double = -1.0
    /** Timestamp (SystemClock.elapsedRealtime) when lastPollerPositionSec was captured — for interpolation. */
    @Volatile private var lastPollerPositionAt: Long = 0
    /** Whether the poller's last known state was playing — for interpolation decisions. */
    @Volatile private var lastPollerIsPlaying: Boolean = false
    /** True once the native MA API recently played fetch has succeeded at least once. */
    @Volatile private var nativeRecentlyPlayedLoaded: Boolean = false

    /** Sendspin connection manager — manages the embedded Sendspin client for multi-room audio. */
    private var sendspinManager: com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager? = null
    private var sendspinDiscovery: com.dashieapp.Dashie.halite.music.sendspin.SendspinDiscoveryManager? = null

    // ── Phase 10.5: Modular music state components ──
    internal var musicCoordinator: com.dashieapp.Dashie.halite.music.MusicStateCoordinator? = null
    private var musicPoller: com.dashieapp.Dashie.halite.music.MaPollerService? = null
    private var musicCommandRouter: com.dashieapp.Dashie.halite.music.MusicCommandRouter? = null
    private var musicSource: com.dashieapp.Dashie.halite.music.MaSendspinSource? = null
    private var speakerGroupController: com.dashieapp.Dashie.halite.music.SpeakerGroupController? = null

    /** Speaker drawer polling handler — polls players/all every 3s while drawer is open. */
    private val speakerPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var speakerPollRunnable: Runnable? = null

    /**
     * Device-name-change receiver. Held here (with its host activity) so re-runs of
     * wireMusicPlayerCallbacks() — every nightly WebView memory-recovery recreation —
     * don't register a duplicate. N stacked receivers meant N Sendspin restarts per
     * ACTION_RESTART_SENDSPIN broadcast.
     */
    private var sendspinRestartReceiver: android.content.BroadcastReceiver? = null
    private var sendspinReceiverHost: android.app.Activity? = null
    @Volatile private var speakerDrawerOpen = false

    /** Last known speakers/groups from fetchAndUpdateSpeakers — available even when drawer is closed. */
    @Volatile private var lastKnownSpeakers: List<com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.SpeakerInfo> = emptyList()
    @Volatile private var lastKnownGroups: List<com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.GroupInfo> = emptyList()

    /** Stop the Sendspin connection. Called from MainActivity.onDestroy(). */
    fun stopSendspin() {
        stopSpeakerPolling()
        sendspinManager?.stop()
        sendspinManager = null
    }

    /**
     * Stop the loops that the next wireMusicPlayerCallbacks() run will re-create.
     * Called from MainWebViewRecreation BEFORE wireAll(). Without this, every
     * nightly memory-recovery recreation leaked a running MaPollerService (network
     * thread per tick) and a MusicStateCoordinator 1s UI tick — their stop()
     * methods previously had no callers — leaving N generations polling MA and
     * fighting the new ones over the music overlay.
     *
     * The Sendspin client itself is NOT stopped here: it is WebView-independent
     * and startSendspinIfNeeded() on the re-wire is a no-op while connected.
     */
    fun teardownForRecreation() {
        stopSpeakerPolling()
        musicPoller?.stop()
        musicPoller = null
        musicCoordinator?.stop()
        musicCoordinator = null
        // The AI music tool holds the coordinator — drop it; the re-wire registers a fresh one.
        com.dashieapp.Dashie.halite.music.MusicVoiceTool.instance = null
    }

    /** Quick refresh of lastKnownSpeakers/lastKnownGroups from MA API. Called on command failure. */
    private fun refreshSpeakerCache(apiClient: com.dashieapp.Dashie.halite.music.MaApiClient) {
        try {
            val arr = apiClient.getPlayers() ?: return
            val groups = mutableListOf<com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.GroupInfo>()
            val speakers = mutableListOf<com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.SpeakerInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val playerId = obj.optString("player_id", "")
                if (playerId.isEmpty()) continue
                val displayName = obj.optString("display_name", "").ifEmpty { obj.optString("name", "") }
                val playerType = obj.optString("type", "player")
                val available = obj.optBoolean("available", true)
                if (playerType == "group") {
                    val members = mutableListOf<String>()
                    val memberArr = obj.optJSONArray("group_members") ?: obj.optJSONArray("group_childs")
                    if (memberArr != null) { for (j in 0 until memberArr.length()) members.add(memberArr.getString(j)) }
                    groups.add(com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.GroupInfo(
                        groupId = playerId, displayName = displayName, memberIds = members
                    ))
                } else if (playerType == "player" || playerType == "stereo_pair") {
                    speakers.add(com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.SpeakerInfo(
                        playerId = playerId, displayName = displayName, available = available
                    ))
                }
            }
            lastKnownSpeakers = speakers
            lastKnownGroups = groups
        } catch (e: Exception) {
            Log.w(TAG, "🎵 refreshSpeakerCache failed: ${e.message}")
        }
    }

    /** Configure the visibility store with HA connection info and fetch latest global hides. */
    private fun configureVisibilityStore(registry: HaliteComponentRegistry, musicPlayer: com.dashieapp.Dashie.halite.music.MusicPlayerOverlayManager) {
        val creds = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(registry.prefs)
        if (creds != null) {
            musicPlayer.configureVisibilityStore(creds.first, creds.second)
        }
    }

    /** Fetch speaker data from MA API and update the drawer. */
    /** Guard to prevent multiple speaker fetch threads from running concurrently. */
    private val speakerFetchActive = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun fetchAndUpdateSpeakers(registry: HaliteComponentRegistry, musicPlayer: com.dashieapp.Dashie.halite.music.MusicPlayerOverlayManager) {
        if (MaApiClient.isCircuitOpen()) {
            Log.d(TAG, "🎵 fetchAndUpdateSpeakers: circuit open, skipping")
            return
        }
        if (!speakerFetchActive.compareAndSet(false, true)) {
            Log.d(TAG, "🎵 fetchAndUpdateSpeakers: already running, skipping")
            return
        }
        val apiClient = createMaApiClient(registry) ?: run {
            speakerFetchActive.set(false)
            return
        }
        Thread {
            try {
                val arr = apiClient.getPlayers() ?: return@Thread
                val thisDeviceId = "up${com.dashieapp.Dashie.halite.music.sendspin.SendspinSettings.getPlayerId()}"
                val groups = mutableListOf<com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.GroupInfo>()
                val speakers = mutableListOf<com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.SpeakerInfo>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val playerId = obj.optString("player_id", "")
                    if (playerId.isEmpty()) continue
                    val displayName = obj.optString("display_name", "").ifEmpty { obj.optString("name", "") }
                    val playerType = obj.optString("type", "player")
                    val provider = obj.optString("provider", "")
                    val state = obj.optString("playback_state", obj.optString("state", "idle"))
                    val volume = obj.optInt("volume_level", 50)
                    val muted = obj.optBoolean("volume_muted", false)
                    val currentMedia = obj.optJSONObject("current_media")
                    val currentTrack = currentMedia?.optString("title", "") ?: ""
                    val currentArtist = currentMedia?.optString("artist", "") ?: ""
                    val currentImageUrl = currentMedia?.optString("image_url", "") ?: ""
                    val currentDuration = currentMedia?.optInt("duration", 0) ?: 0
                    val available = obj.optBoolean("available", true)
                    val syncedTo = if (obj.isNull("synced_to")) "" else obj.optString("synced_to", "")

                    if (playerType == "group") {
                        val members = mutableListOf<String>()
                        val memberArr = obj.optJSONArray("group_members") ?: obj.optJSONArray("group_childs")
                        if (memberArr != null) {
                            for (j in 0 until memberArr.length()) members.add(memberArr.getString(j))
                        }
                        val staticMembers = mutableListOf<String>()
                        val staticArr = obj.optJSONArray("static_group_members")
                        if (staticArr != null) {
                            for (j in 0 until staticArr.length()) staticMembers.add(staticArr.getString(j))
                        }
                        val groupVolume = obj.optInt("group_volume", -1)
                        val groupVolumeMuted = obj.optBoolean("group_volume_muted", false)
                        groups.add(com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.GroupInfo(
                            groupId = playerId, displayName = displayName,
                            groupType = when (provider) { "universal_group" -> "universal_group"; else -> "sync_group" },
                            memberIds = members, staticMemberIds = staticMembers,
                            state = state, currentTrack = currentTrack,
                            currentArtist = currentArtist, currentImageUrl = currentImageUrl,
                            currentDuration = currentDuration,
                            groupVolume = groupVolume, groupVolumeMuted = groupVolumeMuted
                        ))
                    } else if (playerType == "player" || playerType == "stereo_pair") {
                        speakers.add(com.dashieapp.Dashie.halite.music.SpeakerGroupDrawer.SpeakerInfo(
                            playerId = playerId, displayName = displayName,
                            state = state, volumePercent = volume, isMuted = muted, provider = provider,
                            currentTrack = currentTrack, currentArtist = currentArtist,
                            currentImageUrl = currentImageUrl, currentDuration = currentDuration,
                            available = available, syncedTo = syncedTo
                        ))
                    }
                }

                // Log what MA reports for debugging speaker state issues
                for (g in groups) Log.d(TAG, "🎵 MA group: ${g.displayName} state=${g.state} track=${g.currentTrack.take(25)} members=${g.memberIds.size}")
                for (s in speakers) if (s.state != "idle" || !s.available || s.syncedTo.isNotEmpty()) Log.d(TAG, "🎵 MA speaker: ${s.displayName} state=${s.state} avail=${s.available} syncedTo=${s.syncedTo} track=${s.currentTrack.take(25)}")
                lastKnownSpeakers = speakers
                lastKnownGroups = groups

                val currentEntityId = registry.effectiveMusicEntityId
                // Resolve idle state — delegates to IdleResolver which pushes
                // to coordinator callbacks (onIdleResolution, onTargetEntityChanged)
                val coordinator = musicCoordinator
                // Pre-cache groups in coordinator so onTargetEntityChanged can
                // evaluate group state (it fires before the main-thread updateSpeakerDrawer)
                if (coordinator != null && groups.isNotEmpty()) {
                    coordinator.updateSpeakerGroupState(groups, speakers, registry.effectiveMusicEntityId, thisDeviceId)
                }
                val resolver = if (coordinator != null) {
                    com.dashieapp.Dashie.halite.music.IdleResolver(
                        callbacks = coordinator,
                        imageUrlResolver = { relativePath ->
                            val apiUrl = registry.prefs.connection.getEffectiveMaApiUrl()
                            if (apiUrl.isNotEmpty()) "$apiUrl$relativePath" else relativePath
                        }
                    )
                } else null
                val result = resolver?.resolve(groups, speakers, thisDeviceId, currentEntityId)
                // When user has explicitly selected a target, use that for the drawer
                // instead of the auto-detected result
                val coord = musicCoordinator
                val currentTargetId = if (coord != null && coord.userSelectedTarget) {
                    coord.targetEntity.ifEmpty { result?.targetEntityId ?: currentEntityId }
                } else {
                    result?.targetEntityId ?: currentEntityId
                }
                // Update registry for legacy code paths
                if (result?.playingGroupId != null && registry.currentMusicEntityId != result.playingGroupId) {
                    registry.currentMusicEntityId = result.playingGroupId
                }
                val defaultEntity = registry.prefs.connection.musicPlayerDefaultEntityId

                // Persist speaker bar text so it's fresh even before the player card exists.
                // This ensures showWithLastKnownState uses the correct group name.
                if (currentTargetId.isNotEmpty()) {
                    val formalGroup = groups.find { grp -> grp.groupId == currentTargetId }
                    val adHocFollowers = speakers.count { spk -> spk.syncedTo == currentTargetId }
                    val speakerName = speakers.find { spk -> spk.playerId == currentTargetId }?.displayName ?: ""
                    val barText = formalGroup?.displayName
                        ?: if (adHocFollowers > 0 && speakerName.isNotEmpty()) "$speakerName +$adHocFollowers"
                        else speakerName
                    if (barText.isNotEmpty()) {
                        val isGroup = formalGroup != null || adHocFollowers > 0
                        val speakerCount = formalGroup?.memberIds?.size ?: if (adHocFollowers > 0) 1 + adHocFollowers else 0
                        val speakerPrefs = registry.activityRef.getSharedPreferences("dashie_speaker_recents", android.content.Context.MODE_PRIVATE)
                        speakerPrefs.edit()
                            .putString("last_speaker_bar_text", barText)
                            .putBoolean("last_is_group", isGroup)
                            .putInt("last_speaker_count", speakerCount)
                            .apply()
                    }
                }

                // Ad-hoc sync detection: if this device has syncedTo pointing to a lead player,
                // switch the poller target to the lead so we get correct elapsed_time.
                val thisSpk = speakers.find { spk -> thisDeviceId.isNotEmpty() && spk.playerId.contains(thisDeviceId) }
                val coordinator2 = musicCoordinator
                if (thisSpk != null && thisSpk.syncedTo.isNotEmpty() && coordinator2 != null) {
                    val leadId = thisSpk.syncedTo
                    if (coordinator2.targetEntity != leadId) {
                        Log.i(TAG, "🎵 Ad-hoc sync detected: this device synced to lead '$leadId' — switching poller target")
                        coordinator2.onTargetEntityChanged(leadId, isPlaying = thisSpk.state == "playing")
                    }
                }

                // Push group volume state to coordinator (single source of truth)
                val targetGroup = groups.find { grp -> grp.groupId == currentTargetId }
                val isGroupTarget = targetGroup != null
                    || speakers.any { spk -> spk.syncedTo == currentTargetId }
                if (coordinator2 != null && !coordinator2.isVolumeDebounceActive()) {
                    val gv = targetGroup?.groupVolume ?: -1
                    val gm = targetGroup?.groupVolumeMuted ?: false
                    coordinator2.updateGroupVolumeFromPoll(gv, gm, isGroupTarget)
                    // Also update individual volume from this device's speaker
                    if (thisSpk != null) {
                        coordinator2.updateIndividualVolume(thisSpk.volumePercent, thisSpk.isMuted)
                    }
                }

                // Auto-reconnect Sendspin if this device's player is missing from MA
                val thisPlayerFound = thisDeviceId.isNotEmpty() && speakers.any { spk -> spk.playerId.contains(thisDeviceId) }
                if (thisDeviceId.isNotEmpty() && !thisPlayerFound) {
                    missingPlayerPollCount++
                    if (missingPlayerPollCount == MISSING_PLAYER_RECONNECT_THRESHOLD) {
                        Log.w(TAG, "🎵 This device's player ($thisDeviceId) missing from MA for $MISSING_PLAYER_RECONNECT_THRESHOLD polls — triggering Sendspin reconnect")
                        val connMgr = com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalConnectionManager
                        if (connMgr != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                connMgr.stop()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ connMgr.start() }, 2000)
                            }
                        }
                    }
                } else {
                    missingPlayerPollCount = 0
                }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    musicPlayer.updateSpeakerDrawer(speakers, groups, thisDeviceId, currentTargetId, defaultEntity)
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎵 Failed to fetch speakers: ${e.message}")
            } finally {
                speakerFetchActive.set(false)
            }
        }.start()
    }

    /** Start polling speakers every 3s while the drawer is open. */
    private fun startSpeakerPolling(registry: HaliteComponentRegistry, musicPlayer: com.dashieapp.Dashie.halite.music.MusicPlayerOverlayManager) {
        stopSpeakerPolling()
        val runnable = object : Runnable {
            override fun run() {
                if (!speakerDrawerOpen) return
                fetchAndUpdateSpeakers(registry, musicPlayer)
                speakerPollHandler.postDelayed(this, 3000)
            }
        }
        speakerPollRunnable = runnable
        speakerPollHandler.postDelayed(runnable, 3000)
    }

    /** Stop the speaker drawer polling. */
    private fun stopSpeakerPolling() {
        speakerPollRunnable?.let { speakerPollHandler.removeCallbacks(it) }
        speakerPollRunnable = null
    }

    /** Get the active Sendspin client for routing playback commands, or null if not connected. */
    private fun getActiveSendspinClient(): com.dashieapp.Dashie.halite.music.sendspin.SendSpinClient? {
        val client = com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.activeClient
        return if (client?.isConnected == true) client else null
    }

    /**
     * Create an MaApiClient if we have a token and URL, or null.
     * This is the preferred path for all MA communication.
     *
     * Always provides relay URL + HA token so the client can fall back
     * to routing through HA when the MA server is unreachable directly
     * (e.g. remote access via Cloudflare tunnel).
     */
    internal fun createMaApiClient(registry: HaliteComponentRegistry): MaApiClient? {
        val conn = registry.prefs.connection
        if (!conn.hasMaApiToken) return null
        val apiUrl = conn.getEffectiveMaApiUrl()
        if (apiUrl.isEmpty()) return null

        return MaApiClient(
            baseUrl = apiUrl,
            token = conn.maApiToken
        ).also { client ->
            client.onAuthFailure = { handleMaAuthFailure(registry) }
        }
    }

    /**
     * Create a user-scoped MA API client for the active music profile.
     * Play commands should use this so MA records play history under
     * the correct user. Falls back to admin client if no profile is active.
     */
    internal fun createProfileScopedMaApiClient(registry: HaliteComponentRegistry): MaApiClient? {
        val musicPlayer = registry.musicPlayerManager ?: return createMaApiClient(registry)
        val profileMgr = musicPlayer.musicProfileManager ?: return createMaApiClient(registry)
        val userClient = profileMgr.getUserScopedClient()
        if (userClient != null) {
            Log.d(TAG, "🎵 Using profile-scoped API client for active profile")
            userClient.onAuthFailure = { handleMaAuthFailure(registry) }
            return userClient
        }
        return createMaApiClient(registry)
    }

    /** Track whether we've already shown the auth failure prompt this session. */
    // Guards the re-login dialog. Re-armable: reset whenever the dialog is
    // dismissed, so the prompt is reachable again on the next music interaction.
    // (The old one-shot flag was never reset — one "Later" tap blocked re-login
    // until an app restart.)
    @Volatile private var maLoginPromptActive = false

    /** True when we know the MA token is expired (401 received). */
    // "Token expired" is tracked in the ConnectionPreferences pref (single source
    // of truth) so it survives across components — MaLoginActivity clears it on a
    // fresh login, and ControlCenterStateProvider reads it for the card state.

    // Coalesces the flood of concurrent 401s into a single central-store check,
    // and throttles re-checks once expired. The old `if (maTokenExpired) return`
    // guard permanently blocked re-checking, so a device that expired once never
    // picked up a fresh long-lived token another device later shared (Samsung
    // stuck on an expired OAuth token while the central store had a good one).
    private val maAuthFailureHandling = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastCentralTokenCheckMs = 0L
    private val centralTokenRecheckMs = 60_000L

    /**
     * Handle MA API 401 — try to refresh from central store, then mark expired.
     * If the central store has a newer token (e.g., another device re-authenticated),
     * use it and update the MaApiClient. Otherwise, mark expired and prompt later.
     */
    private fun handleMaAuthFailure(registry: HaliteComponentRegistry) {
        // Coalesce concurrent 401s (poller + recently-played + browse all fire
        // at once) into a single in-flight central-store check.
        if (!maAuthFailureHandling.compareAndSet(false, true)) return

        // First failure (not yet expired): check the central store immediately.
        // Already expired: re-check periodically — another device may have shared
        // a fresh long-lived token since — but not on every 401 wave. (The old
        // permanent `if (maTokenExpired) return` never re-checked, so an expired
        // device could not auto-recover.)
        val now = android.os.SystemClock.elapsedRealtime()
        if (registry.prefs.connection.maTokenExpired &&
            now - lastCentralTokenCheckMs < centralTokenRecheckMs) {
            maAuthFailureHandling.set(false)
            return
        }
        lastCentralTokenCheckMs = now
        Log.w(TAG, "🎵 MA token got 401 — checking central store for fresh token")
        Thread {
            try {
                val centralToken = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring
                    .fetchCentralMaToken(registry.prefs)
                val localToken = registry.prefs.connection.maApiToken
                if (centralToken != null && centralToken.first != localToken) {
                    // Central store has a different (hopefully newer) token — use it
                    registry.prefs.connection.maApiToken = centralToken.first
                    registry.prefs.connection.maApiUrl = centralToken.second
                    registry.prefs.connection.maTokenExpired = false
                    Log.i(TAG, "🎵 Refreshed MA token from central store — next API call will use it")
                    // No need to update existing client — createMaApiClient() reads from prefs each time
                } else {
                    registry.prefs.connection.maTokenExpired = true  // drives the re-login prompt + control-center "session expired" card
                    Log.w(TAG, "🎵 MA token expired — will prompt on user interaction")
                }
            } finally {
                maAuthFailureHandling.set(false)
            }
        }.start()
    }

    /**
     * Show MA re-login dialog if the token is expired. Call from user-initiated
     * actions (open recently played, browse music, etc.).
     * Returns true if login was prompted.
     */
    fun showMaLoginIfExpired(registry: HaliteComponentRegistry): Boolean {
        if (!registry.prefs.connection.maTokenExpired) return false
        if (maLoginPromptActive) return true  // a prompt is already on screen; re-arms on dismiss
        maLoginPromptActive = true
        Log.i(TAG, "🎵 MA token expired — showing re-login dialog (user-initiated)")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                val activity = registry.activityRef
                // Pre-fill with the user's EXPLICITLY configured MA URL, falling back
                // to the best guess. Making it editable means a user whose MA runs on
                // an independent host/IP (not the HA-embedded add-on) can point the
                // re-login at the right server instead of the HA-derived guess.
                val explicitUrl = registry.prefs.connection.maApiUrl
                val prefillUrl = explicitUrl.ifEmpty { registry.prefs.connection.getEffectiveMaApiUrl() }

                val density = activity.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()

                val container = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundResource(com.dashieapp.Dashie.R.drawable.dialog_background)
                    setPadding(dp(24), dp(20), dp(24), dp(20))
                }
                android.widget.TextView(activity).apply {
                    text = "Music Assistant"
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    container.addView(this)
                }
                android.widget.TextView(activity).apply {
                    text = "Your Music Assistant session has expired. Confirm your Music " +
                        "Assistant address and sign in to restore recently played and browsing."
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, dp(10), 0, dp(12))
                    container.addView(this)
                }
                val urlInput = android.widget.EditText(activity).apply {
                    setText(prefillUrl)
                    hint = "http://192.168.1.50:8095"
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
                    setHintTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
                    textSize = 14f
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    container.addView(this, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
                }

                val dialog = android.app.AlertDialog.Builder(activity)
                    .setView(container)
                    .setCancelable(true)
                    .create()
                // Re-arm on dismiss (Later, Sign In, or tap-outside) so the prompt
                // can appear again on the next music interaction.
                dialog.setOnDismissListener { maLoginPromptActive = false }

                dialog.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes?.apply { dimAmount = 0.5f }
                }

                val buttonRow = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                    setPadding(0, dp(16), 0, 0)
                }
                android.widget.Button(activity).apply {
                    text = "Later"
                    setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
                    isAllCaps = false
                    minimumHeight = dp(40)
                    setPadding(dp(20), 0, dp(20), 0)
                    setOnClickListener { dialog.dismiss() }
                    buttonRow.addView(this)
                }
                android.widget.Button(activity).apply {
                    text = "Sign In"
                    setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
                    isAllCaps = false
                    minimumHeight = dp(40)
                    setPadding(dp(20), 0, dp(20), 0)
                    setOnClickListener {
                        val entered = urlInput.text.toString().trim().trimEnd('/')
                        if (entered.isEmpty()) {
                            urlInput.error = "Enter your Music Assistant URL"
                            return@setOnClickListener
                        }
                        // Pin it so future connects (and the API client) target this server.
                        registry.prefs.connection.maApiUrl = entered
                        dialog.dismiss()
                        val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity
                            .createIntent(activity, entered)
                        activity.startActivity(intent)
                    }
                    buttonRow.addView(this)
                }
                container.addView(buttonRow)

                dialog.show()
                com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
                    .applyImmersiveModeToDialog(dialog)
            } catch (e: Exception) {
                maLoginPromptActive = false  // reset so a later interaction can retry
                Log.e(TAG, "Failed to show MA re-login dialog: ${e.message}")
            }
        }
        return true
    }

    /**
     * Resolve MA connection info for direct-MA mode.
     * Returns (maServer, maEntity) or nulls if not in direct-MA mode.
     * maServer is the streams proxy URL (legacy fallback when no API token).
     */
    private fun resolveMaConnection(registry: HaliteComponentRegistry): Pair<String?, String?> {
        val useHa = registry.prefs.connection.musicUseHaIntegration
        Log.w(TAG, "🎵 resolveMaConnection | useHa=$useHa")
        if (useHa) {
            Log.w(TAG, "🎵 resolveMaConnection → null (HA integration mode)")
            return Pair(null, null)
        }
        val audioMgr = registry.dashieServiceManager?.audioManager
        val audioMgrUrl = audioMgr?.maServerUrl?.takeIf { it.isNotEmpty() }
        val persistedUrl = registry.prefs.connection.maServerUrl.takeIf { it.isNotEmpty() }
        val haUrlRaw = registry.prefs.connection.haUrl
        val haBaseUrlRaw = registry.prefs.connection.haBaseUrl
        val derivedUrl = try {
            val urlStr = haBaseUrlRaw.takeIf { it.isNotEmpty() } ?: haUrlRaw
            val haUrl = java.net.URL(urlStr)
            "${haUrl.protocol}://${haUrl.host}:8097"
        } catch (_: Exception) { null }
        val maServer = audioMgrUrl ?: persistedUrl ?: derivedUrl
        Log.w(TAG, "🎵 resolveMaConnection | audioMgrUrl=$audioMgrUrl, persistedUrl=$persistedUrl, derivedUrl=$derivedUrl → maServer=$maServer")
        val maEntity = registry.currentMusicEntityId.takeIf { it.isNotEmpty() }
            ?: audioMgr?.maEntityId?.takeIf { it.isNotEmpty() }
            ?: registry.effectiveMusicEntityId.takeIf { it.isNotEmpty() }
        Log.w(TAG, "🎵 resolveMaConnection | maEntity=$maEntity")
        return Pair(maServer, maEntity)
    }

    /**
     * Resolve the MA entity for a command, ignoring the useHa gate.
     * Unlike resolveMaConnection(), this returns an entity even when HA integration
     * is enabled — because the MA API can always be used for commands when available.
     *
     * Skips Sendspin player IDs (up*) that aren't valid for MA API play_media calls.
     * These IDs work for player state polling but not for queueing media.
     */
    internal fun resolveEntityForCommand(registry: HaliteComponentRegistry): String? {
        val current = registry.currentMusicEntityId
        val audioMgr = registry.dashieServiceManager?.audioManager?.maEntityId ?: ""
        val effective = registry.effectiveMusicEntityId
        Log.d(TAG, "🎵 resolveEntity: current='$current' audioMgr='$audioMgr' effective='$effective'")

        // Prefer currentMusicEntityId if it's a real MA entity (not Sendspin up* ID)
        if (current.isNotEmpty() && !current.startsWith("up")) return current

        // AudioManager's entity may be a Sendspin ID — skip if so
        if (audioMgr.isNotEmpty() && !audioMgr.startsWith("up")) return audioMgr

        // Settings default (always a media_player.* entity)
        if (effective.isNotEmpty() && !effective.startsWith("up")) return effective

        // Last resort: allow Sendspin ID for non-play_media commands (polling, volume, etc.)
        return current.takeIf { it.isNotEmpty() }
            ?: audioMgr.takeIf { it.isNotEmpty() }
    }

    /**
     * Send a music command via the best available path:
     * 1. MA REST API (if token available) — preferred
     * 2. MA streams proxy (legacy fallback)
     */
    internal fun sendMaCommand(registry: HaliteComponentRegistry, queueId: String, command: String, extraArgs: Map<String, String> = emptyMap()) {
        Thread {
            try {
                val apiClient = createMaApiClient(registry)
                if (apiClient != null) {
                    // The client that ACTUALLY served this command, for the failure branch below.
                    // play_media swaps in a profile-scoped client, and MA's error body is recorded
                    // on the instance that made the call — so reading it off `apiClient` returned
                    // "" and the user got "<speaker> may be offline (unknown error)" for a plain
                    // MA 500, blaming a speaker that was fine (M case 3, Mio 15 artist/82).
                    var errorClient = apiClient
                    // Preferred path: MA REST API
                    val success = when (command) {
                        "play_pause" -> apiClient.sendPlayerCommand(queueId, "play_pause")
                        "volume_set" -> {
                            val level = extraArgs["volume_level"]?.toIntOrNull() ?: 50
                            apiClient.setVolume(queueId, level)
                        }
                        "transfer_queue" -> {
                            val target = extraArgs["to"] ?: return@Thread
                            apiClient.transferQueue(queueId, target)
                        }
                        "play_media" -> {
                            val uri = extraArgs["uri"] ?: return@Thread
                            // Shared continuation policy — voice uses the same helper so the
                            // two paths can't drift (they did, causing the 2026-07-18 bug).
                            val isTrack = MaVoiceSearchResolver.continuationForUri(uri)
                            // Use profile-scoped client so MA records play under the correct user
                            val profileClient = createProfileScopedMaApiClient(registry) ?: apiClient
                            errorClient = profileClient
                            profileClient.playMedia(queueId, uri, radioMode = isTrack)
                        }
                        else -> apiClient.sendPlayerCommand(queueId, command)
                    }
                    if (success) {
                        Log.i(TAG, "🎵 MA API $command for $queueId → OK")
                    } else {
                        val errorText = errorClient.lastErrorText
                        val errorStatus = errorClient.lastErrorStatus
                        // Rule 2: a command that failed and said nothing is the bug. An empty body
                        // here now means MA closed the connection, not that we read the wrong client.
                        Log.e(TAG, "🎵 DROP: MA API $command for $queueId → FAILED: " +
                            errorText.ifBlank { "(no error body from MA)" })
                        com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                            "MUSIC", "DROP: MA $command failed — ${errorText.take(160).ifBlank { "(no body)" }}")
                        // Only show offline speaker toast for playback commands, not stop/close
                        if (command == "stop") return@Thread
                        // Fetch fresh speaker data to identify offline members
                        refreshSpeakerCache(apiClient)
                        // Extract player ID from MA error if available
                        val errorPlayerId = Regex("Player\\s+(\\S+)\\s+is not available").find(errorText)?.groupValues?.get(1) ?: ""
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            // Try to name the specific speaker from MA's error
                            val speakerName = if (errorPlayerId.isNotEmpty()) {
                                lastKnownSpeakers.find { it.playerId == errorPlayerId || it.playerId.contains(errorPlayerId) || errorPlayerId.contains(it.playerId) }?.displayName
                            } else null
                            val errorSnippet = errorText.take(120).ifBlank { "unknown error" }
                            val msg = when {
                                // FIRST, and deliberately ahead of every speaker branch: a 5xx means
                                // MA RECEIVED the request and failed server-side (MaApiClient: "Got a
                                // response — server is reachable (even if 4xx/5xx)"). Nothing is
                                // offline. This branch used to fall through to the speaker-cache
                                // lookup and tell the user a perfectly healthy speaker "may be
                                // offline" — the codebase contradicting a fact it already holds, and
                                // sending them to chase a phantom (Mio 15).
                                errorStatus in 500..599 ->
                                    "Music Assistant couldn't play this (server error $errorStatus)" +
                                        if (errorText.isNotBlank()) ": $errorSnippet" else ""
                                speakerName != null -> "Music Assistant Error: $speakerName may be offline"
                                errorPlayerId.isNotEmpty() -> "Music Assistant Error: $errorPlayerId may be offline"
                                else -> {
                                    // MA didn't name the speaker — check if queueId is a group or a solo player
                                    val group = lastKnownGroups.find { it.groupId == queueId }
                                    val soloSpeaker = lastKnownSpeakers.find {
                                        it.playerId == queueId || it.playerId.contains(queueId) || queueId.contains(it.playerId)
                                    }
                                    when {
                                        group != null -> {
                                            val memberIds = group.memberIds.toSet()
                                            val offlineNames = lastKnownSpeakers
                                                .filter { it.playerId in memberIds && !it.available }
                                                .map { it.displayName }
                                            if (offlineNames.isNotEmpty()) {
                                                "Music Assistant Error: ${offlineNames.joinToString(", ")} may be offline"
                                            } else {
                                                "Music Assistant Error: a speaker in ${group.displayName} may be offline ($errorSnippet)"
                                            }
                                        }
                                        soloSpeaker != null -> "Music Assistant Error: ${soloSpeaker.displayName} may be offline ($errorSnippet)"
                                        else -> "Music Assistant Error: $errorSnippet"
                                    }
                                }
                            }
                            android.widget.Toast.makeText(registry.activityRef, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    // Fallback: streams proxy
                    val (maServer, _) = resolveMaConnection(registry)
                    if (maServer == null) {
                        Log.w(TAG, "🎵 No MA connection for $command")
                        return@Thread
                    }
                    val queryParams = extraArgs.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
                    val suffix = if (queryParams.isNotEmpty()) "?$queryParams" else ""
                    val url = java.net.URL("$maServer/command/$queueId/$command.mp3$suffix")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val code = conn.responseCode
                    Log.i(TAG, "🎵 MA streams $command for $queueId → HTTP $code")
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎵 MA $command failed: ${e.message}")
            }
        }.start()
    }


    /**
     * Wire music player overlay callbacks.
     * Connects JS bridge music updates to the Kotlin music player overlay.
     * Can be called multiple times safely - will only wire if both components are ready.
     */
    fun wireMusicPlayerCallbacks(registry: HaliteComponentRegistry) {
        val musicPlayer = registry.musicPlayerManager
        val jsBridge = registry.jsBridge

        if (musicPlayer == null) {
            if (registry.prefs.connection.musicSpeakerOnly && registry.prefs.connection.musicPlayerEnabled) {
                // Speaker-only mode: no player UI, but still start Sendspin
                Log.d(TAG, "🔊 Speaker-only mode — starting Sendspin without player UI")
                startSendspinIfNeeded(registry)
            } else {
                Log.d(TAG, "🎵 Music player not ready, skipping wiring")
            }
            return
        }
        if (jsBridge == null) {
            Log.d(TAG, "🎵 JS bridge not ready, skipping wiring")
            return
        }
        if (jsBridge.onMusicPlayerUpdate != null) {
            Log.d(TAG, "🎵 Music player already wired, skipping")
            return
        }

        Log.d(TAG, "🎵 Wiring music player callbacks")

        // JavaScript → Kotlin: Legacy JS bridge adapter for non-Sendspin mode
        val jsMusicBridge = com.dashieapp.Dashie.halite.music.JsMusicBridge(
            musicPlayer = musicPlayer,
            isRealtimeSourceConnected = { getActiveSendspinClient() != null },
            isPollerActive = { maApiPollerActive },
            isPollerStale = { System.currentTimeMillis() - lastMaApiPollAt > 10_000 },
            isLocallyPaused = { locallyPaused },
            isNativeRecentlyPlayedLoaded = { nativeRecentlyPlayedLoaded },
            addRecentPlayer = { entityId -> registry.prefs.connection.addRecentMusicPlayer(entityId) }
        )

        jsBridge.onMusicPlayerUpdate = { musicJson ->
            if (registry.prefs.connection.musicPlayerEnabled) jsMusicBridge.onMusicPlayerUpdate(musicJson)
        }
        jsBridge.onMusicPlayerHide = { jsMusicBridge.onMusicPlayerHide() }

        // ── Voice music commands: route through MA REST API ──
        // This handles sendMusicCommand from JS (voice "play songs by X", "pause", etc.)
        // Uses MusicCommandRouter for play/pause/next/previous, MA API for play_media/volume
        jsBridge.onVoiceMusicCommand = { command, paramsJson ->
            Log.i(TAG, "🎵 Voice music command (JS bridge) via MA API: $command")
            Thread {
                try {
                    // Get the active player ID — can be media_player.* or MA internal ID (up*)
                    // MA API uses queue_id which accepts both formats
                    val entity = musicCoordinator?.getCommandEntity()
                        ?: resolveEntityForCommand(registry)
                        ?: registry.prefs.connection.recentMusicPlayerEntityIds
                            .takeIf { it.isNotEmpty() }
                            ?.split(",")?.firstOrNull()?.trim()
                    if (entity == null) {
                        Log.w(TAG, "🎵 No music entity for voice command '$command'")
                        return@Thread
                    }
                    Log.d(TAG, "🎵 Using entity for voice command: $entity")
                    when (command) {
                        "play_media" -> {
                            val profileClient = createProfileScopedMaApiClient(registry)
                                ?: createMaApiClient(registry)
                            if (profileClient != null) {
                                MaVoicePlay.playFromVoiceParams(
                                    profileClient, entity, org.json.JSONObject(paramsJson)
                                )
                            }
                        }
                        "play", "pause", "next", "previous", "stop", "play_pause" ->
                            sendMaCommand(registry, entity, command)
                        "volume_up", "volume_down" -> {
                            val params = org.json.JSONObject(paramsJson)
                            val delta = params.optInt("delta", 10)
                            val currentPct = (musicPlayer.getCurrentData().volumeLevel * 100).toInt()
                            val newVol = if (command == "volume_up") {
                                (currentPct + delta).coerceIn(0, 100)
                            } else {
                                (currentPct - delta).coerceIn(0, 100)
                            }
                            sendMaCommand(registry, entity, "volume_set", mapOf("volume_level" to newVol.toString()))
                            Log.i(TAG, "🎵 Voice $command: $currentPct → $newVol on $entity")
                        }
                        else -> Log.w(TAG, "🎵 Unknown voice music command: $command")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Voice music command failed: ${e.message}", e)
                }
            }.start()
        }

        // ── Playback commands: routed through MusicCommandRouter ──
        musicPlayer.onPlayPauseClicked = { musicCommandRouter?.playPause() }
        musicPlayer.onNextClicked = { musicCommandRouter?.next() }
        musicPlayer.onPreviousClicked = { musicCommandRouter?.previous() }
        musicPlayer.onStopClicked = { musicCommandRouter?.stop() }
        musicPlayer.onShuffleClicked = { musicCommandRouter?.toggleShuffle() }
        musicPlayer.onRepeatClicked = { musicCommandRouter?.cycleRepeat() }
        musicPlayer.onSeekRequested = { positionMs -> musicCommandRouter?.seek(positionMs) }

        // Fix B2: keep the playback overlay in sync with the real MA auth state.
        // When the session token is expired, the overlay shows a "sign in" prompt
        // (not a false "Reconnecting…") and surfaces the re-armable re-login dialog.
        musicPlayer.onIsSessionExpired = { registry.prefs.connection.maTokenExpired }
        musicPlayer.onSessionExpiredSignIn = { showMaLoginIfExpired(registry) }

        musicPlayer.onVolumeChangeClicked = { level ->
            lastVolumeChangeAt = System.currentTimeMillis()
            userSetVolume = level
            val pct = (level * 100).toInt()
            val thisDeviceId = "up${com.dashieapp.Dashie.halite.music.sendspin.SendspinSettings.getPlayerId()}"
            val entity = musicCoordinator?.getCommandEntity() ?: resolveEntityForCommand(registry)
            val isLocalDevice = entity?.let { thisDeviceId.isNotEmpty() && it.contains(thisDeviceId) } ?: true
            val ssClient = getActiveSendspinClient()

            Log.d(TAG, "🎵 Volume: $pct% local=$isLocalDevice entity=$entity")
            musicPlayer.updateSpeakerVolume(thisDeviceId, pct)

            if (isLocalDevice && ssClient != null) {
                // Controlling this device: set Android volume + tell Sendspin
                ssClient.setVolume(level.toDouble())
                try {
                    val am = registry.activityRef.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (pct * maxVol / 100).coerceIn(0, maxVol), 0)
                } catch (_: Exception) {}
            } else if (isLocalDevice && registry.dashieServiceManager != null) {
                // Controlling this device via ExoPlayer
                registry.dashieServiceManager!!.setDeviceVolume(pct)
            }

            // Always send to MA API too (updates MA's volume state for this or remote player)
            val hasMaApi = createMaApiClient(registry) != null && entity != null
            if (hasMaApi) {
                sendMaCommand(registry, entity!!, "volume_set", mapOf("volume_level" to pct.toString()))
            } else {
                val (maServer, maEntity) = resolveMaConnection(registry)
                if (maServer != null && maEntity != null) {
                    sendMaCommand(registry, maEntity, "volume_set", mapOf("volume_level" to pct.toString()))
                } else {
                    jsBridge.sendMusicVolumeSet(level)
                }
            }
        }

        jsBridge.onMusicPlayerEntityNotFound = { entityId -> jsMusicBridge.onMusicPlayerEntityNotFound(entityId) }
        musicPlayer.onEntitySwitched = { entityId, _ -> jsMusicBridge.onEntitySwitched(entityId) }

        // Speaker indicator tapped — show native entity picker
        musicPlayer.onSpeakerClicked = {
            Log.i(TAG, "🎵 Speaker clicked: showing native entity picker")
            val useHa = registry.prefs.connection.musicUseHaIntegration

            // Use the controlled entity (set by speaker switch) if available,
            // otherwise fall back to local ExoPlayer entity or configured default
            val currentEntityId = resolveEntityForCommand(registry) ?: registry.effectiveMusicEntityId
            Log.w(TAG, "🎵 SPEAKER clicked | currentEntityId=$currentEntityId, useHa=$useHa")
            val recentEntities = registry.prefs.connection.getRecentMusicPlayers()
            val hasMedia = musicPlayer.hasActiveMedia()

            val dialogs = com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs(registry.activityRef, registry.prefs)
            dialogs.webView = registry.webViewRef

            val onSelected = { newEntityId: String ->
                if (newEntityId != currentEntityId) {
                    val newName = dialogs.displayNameFor(newEntityId)
                    if (hasMedia) {
                        dialogs.showTransferConfirmation(registry.activityRef) { action ->
                            when (action) {
                                "transfer" -> {
                                    val hasApi = createMaApiClient(registry) != null
                                    val (maServer, _) = if (!hasApi) resolveMaConnection(registry) else Pair(null, null)
                                    if (hasApi || maServer != null) {
                                        sendMaCommand(registry, currentEntityId, "transfer_queue", mapOf("to" to newEntityId))
                                        registry.prefs.connection.setMusicPlayerEntityAndName(newEntityId, newName)
                                    } else {
                                        dialogs.transferQueue(currentEntityId, newEntityId)
                                        jsBridge.setMusicPlayerEntityWithName(newEntityId, newName)
                                    }
                                    registry.currentMusicEntityId = newEntityId
                                    // Signal the poller to refresh immediately and reset position tracking
                                    forcePollerRefresh = true
                                    val current = musicPlayer.getCurrentData()
                                    musicPlayer.updateState(current.copy(
                                        entityId = newEntityId,
                                        isPlaying = true
                                    ))
                                }
                                "switch" -> {
                                    registry.currentMusicEntityId = newEntityId
                                    if (useHa) jsBridge.setMusicPlayerEntityWithName(newEntityId, newName)
                                }
                            }
                        }
                    } else {
                        registry.currentMusicEntityId = newEntityId
                        if (useHa) jsBridge.setMusicPlayerEntityWithName(newEntityId, newName)
                    }
                }
            }

            val onSetDefault: (String) -> Unit = { entityId ->
                val name = dialogs.displayNameFor(entityId)
                registry.prefs.connection.musicPlayerDefaultEntityId = entityId
                registry.prefs.connection.setMusicPlayerEntityAndName(entityId, name)
                registry.currentMusicEntityId = entityId
                Log.i(TAG, "🎵 Set default music player: $entityId ($name)")
            }

            val apiClient = createMaApiClient(registry)
            val (maServer, _) = if (apiClient == null) resolveMaConnection(registry) else Pair(null, null)
            Log.w(TAG, "🎵 SPEAKER picker decision | hasApi=${apiClient != null}, maServer=$maServer, useHa=$useHa")
            if (apiClient != null) {
                // Preferred: MA REST API for player list
                Thread {
                    try {
                        var client = apiClient
                        var arr = client.getPlayers()
                        // If 401 (stale token), try refreshing from central store and retry once
                        if (arr == null && client.authFailed) {
                            Log.w(TAG, "🎵 Speaker picker got 401 — checking central store for fresh token")
                            val centralToken = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring
                                .fetchCentralMaToken(registry.prefs)
                            val localToken = registry.prefs.connection.maApiToken
                            if (centralToken != null && centralToken.first != localToken) {
                                registry.prefs.connection.maApiToken = centralToken.first
                                registry.prefs.connection.maApiUrl = centralToken.second
                                Log.i(TAG, "🎵 Refreshed MA token from central store — retrying getPlayers()")
                                client = createMaApiClient(registry) ?: client
                                arr = client.getPlayers()
                            }
                        }
                        if (arr == null) {
                            Handler(Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    registry.activityRef,
                                    "Music Assistant unavailable — try again",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@Thread
                        }
                        val players = (0 until arr.length()).map { i ->
                            val obj = arr.getJSONObject(i)
                            val playerId = obj.optString("player_id", "")
                            val displayName = obj.optString("display_name", "")
                            val name = obj.optString("name", "")
                            val model = obj.optJSONObject("device_info")?.optString("model", "") ?: ""
                            val friendlyName = com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs
                                .resolveMaFriendlyName(playerId, displayName, name, model)
                            com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs.MediaPlayerInfo(
                                entityId = playerId,
                                friendlyName = friendlyName,
                                state = obj.optString("state", "idle")
                            )
                        }.filter { it.entityId.isNotEmpty() }
                        Log.i(TAG, "🎵 MA API players: ${players.size} available")
                        Handler(Looper.getMainLooper()).post {
                            dialogs.showMediaPlayerPickerDialogDirect(
                                currentEntityId, players, recentEntities, onSelected, onSetDefault
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "🎵 Failed to fetch MA API players: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                registry.activityRef,
                                "Music Assistant unavailable — try again",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            } else if (maServer != null) {
                // Fallback: MA streams proxy for player list
                Thread {
                    try {
                        val url = java.net.URL("$maServer/players")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        val json = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val arr = org.json.JSONArray(json)
                        val players = (0 until arr.length()).map { i ->
                            val obj = arr.getJSONObject(i)
                            val playerId = obj.optString("player_id", "")
                            val displayName = obj.optString("display_name", "")
                            val name = obj.optString("name", "")
                            val model = obj.optJSONObject("device_info")?.optString("model", "") ?: ""
                            com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs.MediaPlayerInfo(
                                entityId = playerId,
                                friendlyName = com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs
                                    .resolveMaFriendlyName(playerId, displayName, name, model),
                                state = obj.optString("state", "idle")
                            )
                        }.filter { it.entityId.isNotEmpty() }
                        Log.i(TAG, "🎵 MA streams players: ${players.size} available")
                        Handler(Looper.getMainLooper()).post {
                            dialogs.showMediaPlayerPickerDialogDirect(
                                currentEntityId, players, recentEntities, onSelected, onSetDefault
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "🎵 Failed to fetch MA players: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                registry.activityRef,
                                "Music server unavailable — try again in a moment",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            } else {
                // No MA API or streams server — offer to set up MA login
                val maUrl = registry.prefs.connection.getEffectiveMaApiUrl()
                if (maUrl.isNotEmpty()) {
                    val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(registry.activityRef, maUrl)
                    registry.activityRef.startActivity(intent)
                } else {
                    android.widget.Toast.makeText(
                        registry.activityRef,
                        "Music Assistant not configured — check HA connection",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Speaker group drawer: fetch speakers when drawer opens, poll while open.
        // The callback fires on EVERY toggle — check if polling is already running
        // to determine if we're opening (start) or closing (stop).
        musicPlayer.onSpeakerDrawerToggle = {
            if (speakerPollRunnable == null) {
                // Not polling → drawer is opening
                // Configure visibility store for HA sync + fetch latest global hides
                configureVisibilityStore(registry, musicPlayer)
                speakerDrawerOpen = true
                Log.i(TAG, "🎵 Speaker drawer opened — fetching players + starting poll")
                fetchAndUpdateSpeakers(registry, musicPlayer)
                startSpeakerPolling(registry, musicPlayer)
            } else {
                // Already polling → drawer is closing
                speakerDrawerOpen = false
                Log.i(TAG, "🎵 Speaker drawer closed — stopping poll")
                stopSpeakerPolling()
            }
        }

        // Speaker group drawer: all interactions routed through SpeakerGroupController
        musicPlayer.onSpeakerJoin = { playerId, join -> speakerGroupController?.onSpeakerJoin(playerId, join) }
        musicPlayer.onSpeakerVolumeChange = { playerId, percent -> speakerGroupController?.onSpeakerVolumeChange(playerId, percent) }
        musicPlayer.onSpeakerMuteToggle = { playerId, muted -> speakerGroupController?.onSpeakerMuteToggle(playerId, muted) }
        musicPlayer.onGroupVolumeChange = { percent -> speakerGroupController?.onGroupVolumeChange(percent) }
        musicPlayer.onGroupMuteToggle = { muted -> speakerGroupController?.onGroupMuteToggle(muted) }
        musicPlayer.onTransferQueue = { targetPlayerId, targetName -> speakerGroupController?.onTransferQueue(targetPlayerId, targetName) }
        musicPlayer.onClearQueue = { targetPlayerId -> speakerGroupController?.onClearQueue(targetPlayerId) }

        jsBridge.onRecentlyPlayedUpdate = { json -> jsMusicBridge.onRecentlyPlayedUpdate(json) }

        // Recently played: direct MA API fetch (preferred — better image URLs, no HA auth needed)
        fetchRecentlyPlayedFromApi(registry, musicPlayer)

        // Media browser: wire up data source for library browsing
        createMaApiClient(registry)?.let { apiClient ->
            val maApiUrl = registry.prefs.connection.getEffectiveMaApiUrl()
            val dataSource = com.dashieapp.Dashie.halite.music.MediaBrowserDataSource(apiClient, maApiUrl)
            musicPlayer.setMediaBrowserDataSource(dataSource)
        }

        // Recently played: tap to play (Kotlin → MA API or JS)
        musicPlayer.onPlayRecentItemClicked = { uri ->
            // Reset position immediately to prevent stale position flash (e.g. 93:56)
            com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.pendingPositionReset = true
            val apiClient = createMaApiClient(registry)
            val ssActive = getActiveSendspinClient() != null
            val sendspinId = "up${com.dashieapp.Dashie.halite.music.sendspin.SendspinSettings.getPlayerId()}"
            // Use coordinator for entity, fall back to legacy resolution
            val entity = musicCoordinator?.getCommandEntity()
                ?: registry.currentMusicEntityId.takeIf { it.isNotEmpty() }
                ?: if (ssActive) sendspinId else resolveEntityForCommand(registry)
            Log.w(TAG, "🎵 RECENT ITEM clicked | uri='$uri' entity='$entity' sendspin=$ssActive hasApi=${apiClient != null}")
            if (apiClient != null) {
                if (entity != null) {
                    sendMaCommand(registry, entity, "play_media", mapOf("uri" to uri))
                } else {
                    // No entity yet — try to discover one from the API
                    Thread {
                        try {
                            val players = apiClient.getPlayers()
                            val firstPlayer = players?.let { arr ->
                                (0 until arr.length()).map { arr.getJSONObject(it) }
                                    .firstOrNull { it.optString("state") == "playing" || it.optString("state") == "paused" }
                                    ?: if (arr.length() > 0) arr.getJSONObject(0) else null
                            }
                            val playerId = firstPlayer?.optString("player_id", "")?.takeIf { it.isNotEmpty() }
                            if (playerId != null) {
                                Log.i(TAG, "🎵 Auto-discovered player for play_media: $playerId")
                                registry.currentMusicEntityId = playerId
                                sendMaCommand(registry, playerId, "play_media", mapOf("uri" to uri))
                            } else {
                                Log.w(TAG, "🎵 No players available for play_media")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "🎵 Failed to discover player for play_media: ${e.message}")
                        }
                    }.start()
                }
            } else {
                // Fallback: streams proxy or JS
                val (maServer, maEntity) = resolveMaConnection(registry)
                if (maServer != null && maEntity != null) {
                    sendMaCommand(registry, maEntity, "play_media", mapOf("uri" to uri))
                } else {
                    jsBridge.sendPlayRecentItem(uri)
                }
            }
        }

        // REST API → Music Player: metadata pushed by Music Assistant provider
        val audioManager = registry.dashieServiceManager?.audioManager
        val serviceManager = registry.dashieServiceManager
        if (audioManager != null && serviceManager != null) {
            val mainHandler = Handler(Looper.getMainLooper())

            audioManager.onMediaInfoReceived = mediaInfo@{ title, artist, album, imageUrl, durationMs ->
                val current = musicPlayer.getCurrentData()
                val isNewTrack = title.isNotEmpty() && title != current.trackName
                Log.i(TAG, "🎵 setMediaInfo: '$title' by '$artist' (duration=${durationMs}ms, newTrack=$isNewTrack, pollerActive=$maApiPollerActive)")
                // Store in push fields (on JsMusicBridge) — these survive JS re-injection resets
                if (title.isNotEmpty()) jsMusicBridge.pushTrackName = title
                if (artist.isNotEmpty()) jsMusicBridge.pushArtistName = artist
                jsMusicBridge.pushAlbumName = album
                if (imageUrl.isNotEmpty()) jsMusicBridge.pushAlbumArtUrl = imageUrl
                if (durationMs > 0) jsMusicBridge.pushDurationSeconds = durationMs / 1000
                jsMusicBridge.pushIsPlaying = true
                // Persist MA server URL so speaker picker works without active playback
                audioManager.maServerUrl.takeIf { it.isNotEmpty() }?.let {
                    registry.prefs.connection.maServerUrl = it
                }
                // When the poller is active, let it be the sole UI updater to prevent
                // bounce/flicker from competing update sources during track transitions.
                // Push fields are still set above so the poller can use them if needed.
                if (maApiPollerActive && (System.currentTimeMillis() - lastMaApiPollAt) < 10_000) {
                    Log.d(TAG, "🎵 setMediaInfo: poller active — skipping direct UI update")
                    return@mediaInfo
                }
                // Poller not active — update UI directly (fallback for non-MA mode)
                val updatedData = current.copy(
                    trackName = title.ifEmpty { current.trackName },
                    artistName = artist.ifEmpty { current.artistName },
                    albumName = album,
                    albumArtUrl = imageUrl.takeIf { it.isNotEmpty() } ?: current.albumArtUrl,
                    durationSeconds = if (durationMs > 0) durationMs / 1000 else current.durationSeconds,
                    positionSeconds = if (isNewTrack) 0 else current.positionSeconds,
                    isPlaying = true
                )
                mainHandler.post {
                    musicPlayer.updateState(updatedData)
                }
            }
            Log.i(TAG, "🎵 REST API media info → music player wired")

            // ── Phase 10.5: Create modular music state components ──
            val coordinator = com.dashieapp.Dashie.halite.music.MusicStateCoordinator(musicPlayer)
            musicCoordinator = coordinator
            musicPlayer.volumeCoordinator = coordinator

            val source = com.dashieapp.Dashie.halite.music.MaSendspinSource(
                apiClientProvider = { createMaApiClient(registry) },
                sendMaCommand = { entityId, command, args -> sendMaCommand(registry, entityId, command, args) },
                commandEntityProvider = {
                    // Prefer coordinator's target entity (set by idle resolution / group detection)
                    // over stale registry values from previous sessions
                    coordinator.targetEntity.takeIf { it.isNotEmpty() }
                        ?: registry.currentMusicEntityId.takeIf { it.isNotEmpty() }
                        ?: registry.dashieServiceManager?.audioManager?.maEntityId?.takeIf { it.isNotEmpty() }
                        ?: registry.effectiveMusicEntityId.takeIf { it.isNotEmpty() }
                },
                pollerEntityProvider = {
                    coordinator.targetEntity.takeIf { it.isNotEmpty() }
                        ?: registry.currentMusicEntityId.takeIf { it.isNotEmpty() }
                        ?: registry.dashieServiceManager?.audioManager?.maEntityId?.takeIf { it.isNotEmpty() }
                        ?: registry.effectiveMusicEntityId.takeIf { it.isNotEmpty() }
                }
            )
            musicSource = source
            coordinator.setActiveSource(source)

            val poller = com.dashieapp.Dashie.halite.music.MaPollerService(
                callbacks = coordinator,
                apiClientProvider = { createMaApiClient(registry) },
                entityProvider = { coordinator.getPollerEntity() },
                streamsServerProvider = {
                    val audioMgrUrl = audioManager.maServerUrl.takeIf { it.isNotEmpty() }
                    val persistedUrl = registry.prefs.connection.maServerUrl.takeIf { it.isNotEmpty() }
                    val derivedUrl = try {
                        val haUrl = java.net.URL(registry.prefs.connection.haUrl)
                        "${haUrl.protocol}://${haUrl.host}:8097"
                    } catch (_: Exception) { null }
                    audioMgrUrl ?: persistedUrl ?: derivedUrl
                },
                imageUrlResolver = { relativePath ->
                    val apiUrl = registry.prefs.connection.getEffectiveMaApiUrl()
                    if (apiUrl.isNotEmpty()) "$apiUrl$relativePath" else relativePath
                },
                volumeProvider = {
                    serviceManager.getDeviceVolume().coerceIn(0, 100) / 100f
                },
                isRealtimeSourceConnected = { getActiveSendspinClient() != null },
                onEntityNotFound = {
                    registry.currentMusicEntityId = ""
                    registry.prefs.connection.musicPlayerEntityId = ""
                    registry.prefs.connection.musicPlayerDefaultEntityId = ""
                }
            )
            musicPoller = poller

            val router = com.dashieapp.Dashie.halite.music.MusicCommandRouter(
                coordinator = coordinator,
                musicPlayer = musicPlayer,
                sourceProvider = { source },
                sendMaCommand = { entityId, command, args -> sendMaCommand(registry, entityId, command, args) },
                apiClientProvider = { createMaApiClient(registry) }
            )
            musicCommandRouter = router

            // AI `music` tool executor (realtime dispatcher + cascade JS bridge reach it
            // via MusicVoiceTool.instance). Entity fallback chain matches onVoiceMusicCommand.
            com.dashieapp.Dashie.halite.music.MusicVoiceTool.instance =
                com.dashieapp.Dashie.halite.music.MusicVoiceTool(
                    coordinator = coordinator,
                    apiClientProvider = { createProfileScopedMaApiClient(registry) ?: createMaApiClient(registry) },
                    entityResolver = {
                        coordinator.getCommandEntity()
                            ?: resolveEntityForCommand(registry)
                            ?: registry.prefs.connection.recentMusicPlayerEntityIds
                                .takeIf { it.isNotEmpty() }
                                ?.split(",")?.firstOrNull()?.trim()
                    },
                    speakerMatcher = com.dashieapp.Dashie.halite.music.SpeakerNameMatcher(
                        playersProvider = { createMaApiClient(registry)?.getPlayers() }
                    ),
                )

            speakerGroupController = com.dashieapp.Dashie.halite.music.SpeakerGroupController(
                context = registry.activityRef,
                musicPlayer = musicPlayer,
                coordinator = coordinator,
                poller = poller,
                apiClientProvider = { createMaApiClient(registry) },
                sendMaCommand = { entityId, command, args -> sendMaCommand(registry, entityId, command, args) },
                refreshSpeakers = { fetchAndUpdateSpeakers(registry, musicPlayer) },
                setDefaultPlayer = { id -> registry.prefs.connection.setDefaultMusicPlayer(id) },
                setCurrentEntityId = { id -> registry.currentMusicEntityId = id },
                showDialogs = { com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs(registry.activityRef, registry.prefs) }
            )

            // When Sendspin says playing but coordinator has no track,
            // trigger immediate entity resolution to find the playing group
            coordinator.onNeedEntityResolution = {
                Log.i(TAG, "🎵 Immediate entity resolution requested — fetching speakers")
                fetchAndUpdateSpeakers(registry, musicPlayer)
            }

            // When poller detects device is playing as group member (player=playing,
            // queue=idle), re-resolve entity to switch to the group.
            // Skip when userSelectedTarget is set — the user explicitly chose a target
            // and stale polls from the old entity shouldn't override it.
            poller.onGroupMembershipDetected = {
                if (coordinator.userSelectedTarget) {
                    Log.d(TAG, "🎵 Group membership detected but userSelectedTarget set — skipping re-resolution")
                } else {
                    Log.i(TAG, "🎵 Group membership detected by poller — re-resolving entity")
                    fetchAndUpdateSpeakers(registry, musicPlayer)
                }
            }

            // When the polled entity transitions from playing to idle, the device
            // may now be in a different playing group. Re-resolve to find it.
            poller.onTargetWentIdle = {
                Log.i(TAG, "🎵 Target went idle — re-resolving entity")
                fetchAndUpdateSpeakers(registry, musicPlayer)
            }

            // Start the coordinator's UI tick and the poller
            coordinator.start()
            poller.start()


            // Initial speaker fetch to resolve idle track/group for this device
            fetchAndUpdateSpeakers(registry, musicPlayer)

            // Sync device friendly name from HA device registry (background, best-effort).
            // If the name changed (e.g. user renamed in HA), restarts Sendspin after.
            syncDeviceFriendlyName(registry)

            // Start Sendspin client for multi-room speaker capability.
            // Connects to MA's Sendspin server (port 8927) for synchronized audio
            // with Chromecast, AirPlay, and other Sendspin speakers.
            startSendspinIfNeeded(registry)

            // Listen for device name changes from Settings > System > Device Name.
            // Restarts Sendspin so it re-registers with the new friendly name in MA.
            // Registered once per host activity: WebView recreation re-runs this whole
            // function but must not stack another receiver; an Activity recreate gets a
            // fresh registration (after unregistering from the old, now-destroyed host).
            if (sendspinRestartReceiver == null || sendspinReceiverHost !== registry.activityRef) {
                sendspinRestartReceiver?.let { old ->
                    runCatching { sendspinReceiverHost?.unregisterReceiver(old) }
                }
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                        Log.i(TAG, "🔊 Sendspin restart requested (device name changed)")
                        startSendspinIfNeeded(registry)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    registry.activityRef.registerReceiver(
                        receiver,
                        android.content.IntentFilter("com.dashieapp.Dashie.ACTION_RESTART_SENDSPIN"),
                        android.content.Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    registry.activityRef.registerReceiver(
                        receiver,
                        android.content.IntentFilter("com.dashieapp.Dashie.ACTION_RESTART_SENDSPIN")
                    )
                }
                sendspinRestartReceiver = receiver
                sendspinReceiverHost = registry.activityRef
            }
        }

        Log.i(TAG, "🎵 Music player callbacks wired")
    }

    /**
     * Query /api/dashie/device/names on startup to sync this device's friendly name
     * from the HA device registry. Runs on a background thread.
     * If the name changed, restarts Sendspin so it re-registers with the new name.
     */
    private fun syncDeviceFriendlyName(registry: HaliteComponentRegistry, retryCount: Int = 0) {
        val conn = registry.prefs.connection
        val haUrl = (conn.haBaseUrl.takeIf { it.isNotEmpty() } ?: conn.haUrl).trimEnd('/')
        if (haUrl.isEmpty()) return

        val token = conn.haAccessToken
        if (token.isEmpty()) {
            // Token not yet synced from WebView — retry after delay (up to 3 attempts)
            if (retryCount < 3) {
                Log.d(TAG, "🏠 Device name sync: no HA token yet, retry ${retryCount + 1}/3 in 10s")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    syncDeviceFriendlyName(registry, retryCount + 1)
                }, 10_000)
            }
            return
        }

        Thread {
            try {
                // Use getValidCredentialsSync to pre-refresh expired tokens before the request,
                // avoiding 401 responses that trigger HA's IP ban mechanism
                val credentials = HaTokenExtractor.getValidCredentialsSync(registry.prefs)
                if (credentials == null) {
                    if (retryCount < 3) {
                        Log.d(TAG, "🏠 Device name sync: token expired and refresh failed, retry ${retryCount + 1}/3 in 15s")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            syncDeviceFriendlyName(registry, retryCount + 1)
                        }, 15_000)
                    }
                    return@Thread
                }
                val (baseUrl, validToken) = credentials

                val url = java.net.URL("$haUrl${ApiPaths.HA}/device/names")
                val httpConn = url.openConnection() as java.net.HttpURLConnection
                httpConn.setRequestProperty("Authorization", "Bearer $validToken")
                httpConn.connectTimeout = 10000
                httpConn.readTimeout = 10000

                if (httpConn.responseCode == 200) {
                    val body = httpConn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(body)
                    val devices = json.getJSONArray("devices")
                    // Match against this device's HA-side identifier. Post
                    // 1.4.7 integration that's the hardware-tied stable ID;
                    // pre-migration HA installs still have ANDROID_ID, so we
                    // accept either.
                    val stableId = com.dashieapp.Dashie.util.StableDeviceId.read(registry.activityRef)
                    val androidId = android.provider.Settings.Secure.getString(
                        registry.activityRef.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""

                    for (i in 0 until devices.length()) {
                        val device = devices.getJSONObject(i)
                        val deviceAndroidId = device.optString("android_id", "")
                        val matches = (stableId.isNotEmpty() && deviceAndroidId == stableId) ||
                            (androidId.isNotEmpty() && deviceAndroidId == androidId)
                        if (matches) {
                            val name = device.optString("name", "")
                            if (name.isNotEmpty() && name != conn.deviceFriendlyName) {
                                val oldName = conn.deviceFriendlyName
                                conn.deviceFriendlyName = name
                                Log.i(TAG, "🏠 Device friendly name synced from HA: '$oldName' → '$name'")
                                // Restart Sendspin on the main thread with the new name
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    startSendspinIfNeeded(registry)
                                }
                            }
                            break
                        }
                    }
                } else {
                    Log.d(TAG, "🏠 Device name sync: HTTP ${httpConn.responseCode}")
                }
                httpConn.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "🏠 Device name sync error: ${e.message}")
            }
        }.start()
    }

    /**
     * Start the embedded Sendspin client if music is enabled and MA server is known.
     * Makes this Dashie tablet a groupable speaker in Music Assistant.
     * Sendspin is MA's native protocol — enables sync with Chromecast (via Sendspin Bridges),
     * AirPlay, and other Sendspin speakers.
     */
    private fun startSendspinIfNeeded(registry: HaliteComponentRegistry) {
        if (!registry.prefs.connection.musicPlayerEnabled) return

        // Don't restart if already connected
        val existing = sendspinManager
        if (existing != null && existing.isConnected) {
            Log.d(TAG, "🔊 Sendspin: Already connected, skipping")
            return
        }

        val conn = registry.prefs.connection

        // Speaker-only mode with a user-confirmed URL from the connection dialog:
        // use it directly, skipping both the MA-login chain and mDNS discovery.
        // This is the path for users who explicitly set up speaker-only and don't
        // have an MA account (Jordan's use case).
        if (conn.musicSpeakerOnly && conn.speakerOnlyMaUrl.isNotBlank()) {
            Log.i(TAG, "🔊 Speaker-only: using saved URL from connection dialog: ${conn.speakerOnlyMaUrl}")
            startSendspinWithSavedUrl(registry, conn.speakerOnlyMaUrl)
            return
        }

        // Sendspin needs the MA server's LAN IP. Try MA API URL first, fall back to HA URL
        // (MA typically runs on the same host as HA).
        var maUrl = conn.getEffectiveMaApiUrl()
        if (maUrl.isEmpty()) {
            val haUrl = conn.haBaseUrl.takeIf { it.isNotEmpty() } ?: conn.haUrl
            if (haUrl.isNotEmpty()) {
                try {
                    val parsed = java.net.URL(haUrl)
                    maUrl = "http://${parsed.host}:8095"
                    Log.d(TAG, "🔊 Sendspin: No MA API URL, derived from HA URL: $maUrl")
                } catch (_: Exception) {}
            }
        }
        if (maUrl.isEmpty()) {
            if (conn.musicSpeakerOnly) {
                // Speaker-only mode with no saved URL: use mDNS to discover Sendspin server.
                // Should only happen for users who had speaker-only enabled from before the
                // connection dialog existed (pre-2.24.13B) — new enables always populate
                // speakerOnlyMaUrl via the dialog.
                Log.i(TAG, "🔊 Speaker-only: No saved URL — falling back to mDNS discovery")
                startSendspinDiscovery(registry)
                return
            }
            Log.d(TAG, "🔊 Sendspin: No MA server URL, skipping")
            return
        }
        // Device name for Sendspin registration.
        // Priority: ConnectionPreferences.deviceFriendlyName (user-set or synced from HA)
        //         → dashie_api_prefs.device_name (JS-synced, full mode only)
        //         → Build.MODEL (fallback)
        // The friendly name is set during onboarding via /api/dashie/device/name endpoint,
        // or edited by the user in Settings > System > Device Name.
        val apiPrefs = registry.activityRef.getSharedPreferences("dashie_api_prefs", android.content.Context.MODE_PRIVATE)
        val friendlyName = conn.deviceFriendlyName
        val apiDeviceName = apiPrefs.getString("device_name", null)
        val buildModel = android.os.Build.MODEL
        val deviceName = friendlyName.takeIf { it.isNotEmpty() }
            ?: apiDeviceName?.takeIf { it.isNotEmpty() }
            ?: buildModel
        Log.i(TAG, "🔊 Sendspin device name: friendly='$friendlyName' api='$apiDeviceName' model='$buildModel' → '$deviceName'")

        // Stop any existing manager
        sendspinManager?.stop()

        val musicPlayer = registry.musicPlayerManager
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        val manager = com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager(
            context = registry.activityRef.applicationContext,
            maServerUrl = maUrl,
            deviceName = deviceName
        )
        manager.onStateChanged = { state ->
            Log.i(TAG, "🔊 Sendspin state: $state")
            if (state == com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager.State.CONNECTED && musicPlayer != null) {
                // P3 fix: re-run idle resolution after Sendspin connects.
                // On faster devices, Sendspin connects and pushes empty state before
                // the startup idle resolution completes, wiping idle track metadata.
                Log.i(TAG, "🎵 [IDLE] Re-running idle resolution after Sendspin connect")
                fetchAndUpdateSpeakers(registry, musicPlayer)
            }
        }
        sendspinManager = manager

        // Set static callbacks BEFORE starting the service so they're available in onCreate
        com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalConnectionManager = manager
        // Sendspin only provides play/pause state and volume to the coordinator.
        // Track metadata, position, and duration come from the MA poller.
        com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalPlayerDataCallback = { data ->
            mainHandler.post {
                val coordinator = musicCoordinator ?: return@post
                if (!registry.prefs.connection.musicPlayerEnabled) return@post
                // Forward play/pause state
                coordinator.onPlaybackStateChanged(data.isPlaying)
                // Forward volume
                coordinator.onVolumeChanged((data.volumeLevel * 100).toInt(), data.isVolumeMuted)
                Log.d(TAG, "🎵 [SS→COORD] playing=${data.isPlaying} vol=${(data.volumeLevel * 100).toInt()} muted=${data.isVolumeMuted}")
            }
        }

        manager.start()
        Log.i(TAG, "🔊 Sendspin connection manager started (device: $deviceName, server: $maUrl)")
    }

    /**
     * Speaker-only mode with a URL the user confirmed via the connection dialog.
     * Parses the URL into host/port/path (defaults 8927 / /sendspin), then connects.
     */
    private fun startSendspinWithSavedUrl(registry: HaliteComponentRegistry, savedUrl: String) {
        val uri = try { java.net.URI(savedUrl) } catch (_: Exception) { null }
        val host = uri?.host
        if (host.isNullOrBlank()) {
            Log.w(TAG, "🔊 Speaker-only: saved URL has no host, falling back to mDNS: $savedUrl")
            startSendspinDiscovery(registry)
            return
        }
        // Users typically type the MA REST API URL they're familiar with (port 8095) —
        // not the Sendspin WebSocket URL (port 8927, path /sendspin). Port 8095 on the
        // MA server doesn't serve the Sendspin protocol, so blindly using whatever port
        // the user typed would fail. Heuristic: if the port is 8095 (or any other
        // well-known non-Sendspin port) fall back to the Sendspin default. If the port
        // is clearly a Sendspin-family port (8927, or user-specified something custom
        // that's not the MA REST port), honor it.
        val rawPort = uri.port
        val isMaRestPort = rawPort == 8095 || rawPort == 8123  // 8123 = HA itself
        val port = when {
            rawPort > 0 && !isMaRestPort -> rawPort
            else -> com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager.DEFAULT_SENDSPIN_PORT
        }
        val rawPath = uri.path ?: ""
        val path = when {
            rawPath.isEmpty() || rawPath == "/" -> com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager.DEFAULT_SENDSPIN_PATH
            rawPath.startsWith("/") -> rawPath
            else -> "/$rawPath"
        }
        Log.i(TAG, "🔊 Speaker-only URL parse: host=$host rawPort=$rawPort → usingPort=$port path=$path (input=$savedUrl)")

        val conn = registry.prefs.connection
        val deviceName = conn.deviceFriendlyName.takeIf { it.isNotEmpty() }
            ?: android.os.Build.MODEL

        sendspinManager?.stop()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val manager = com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager(
            context = registry.activityRef.applicationContext,
            maServerUrl = "http://$host:${uri.port.takeIf { it > 0 } ?: 8095}",
            deviceName = deviceName,
            sendspinPort = port,
            sendspinPath = path
        )
        manager.onStateChanged = { state ->
            Log.i(TAG, "🔊 Sendspin (saved URL) state: $state")
        }
        sendspinManager = manager

        com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalConnectionManager = manager
        com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalPlayerDataCallback = { data ->
            mainHandler.post {
                Log.d(TAG, "🔊 [SS-SPEAKER] playing=${data.isPlaying} track=${data.trackName}")
            }
        }

        manager.start()
        Log.i(TAG, "🔊 Sendspin started from saved URL: $host:$port$path")
    }

    /**
     * Speaker-only mode: discover Sendspin server via mDNS, then connect.
     * Called when no MA/HA URL is configured (pure speaker-only setup).
     */
    private fun startSendspinDiscovery(registry: HaliteComponentRegistry) {
        sendspinDiscovery?.stopDiscovery()

        val discovery = com.dashieapp.Dashie.halite.music.sendspin.SendspinDiscoveryManager(
            registry.activityRef.applicationContext
        )
        discovery.onServerDiscovered = { server ->
            Log.i(TAG, "🔊 mDNS discovered Sendspin server: ${server.name} at ${server.host}:${server.port}${server.path}")
            // Stop discovery once we have a server
            discovery.stopDiscovery()
            // Connect using the discovered host
            val conn = registry.prefs.connection
            val deviceName = conn.deviceFriendlyName.takeIf { it.isNotEmpty() }
                ?: android.os.Build.MODEL

            sendspinManager?.stop()
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            // maServerUrl's port here is MA's REST API port (8095) — only used to extract
            // the host for the Sendspin connection below. The actual Sendspin WebSocket
            // connection uses the resolved port/path from the mDNS record.
            val manager = com.dashieapp.Dashie.halite.music.sendspin.SendspinConnectionManager(
                context = registry.activityRef.applicationContext,
                maServerUrl = "http://${server.host}:8095",
                deviceName = deviceName,
                sendspinPort = server.port,
                sendspinPath = server.path
            )
            manager.onStateChanged = { state ->
                Log.i(TAG, "🔊 Sendspin (discovered) state: $state")
            }
            sendspinManager = manager

            com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalConnectionManager = manager
            com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.globalPlayerDataCallback = { data ->
                mainHandler.post {
                    // Speaker-only: no coordinator or player UI, just log
                    Log.d(TAG, "🔊 [SS-SPEAKER] playing=${data.isPlaying} track=${data.trackName}")
                }
            }

            manager.start()
            Log.i(TAG, "🔊 Sendspin (discovered) started: ${server.name} at ${server.host}")
        }
        discovery.onDiscoveryFailed = { error ->
            Log.w(TAG, "🔊 Sendspin mDNS discovery failed: $error")
        }

        sendspinDiscovery = discovery
        discovery.startDiscovery()
    }

    /**
     * Fetch recently played items directly from the MA REST API.
     * Runs once on startup, then periodically. Image URLs from MA are absolute
     * and don't need HA auth, solving the artwork loading problem.
     */
    /** Guard to prevent multiple fetch threads from running concurrently. */
    private val recentlyPlayedFetchActive = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun fetchRecentlyPlayedFromApi(
        registry: HaliteComponentRegistry,
        musicPlayer: com.dashieapp.Dashie.halite.music.MusicPlayerOverlayManager
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val fetchRunnable = object : Runnable {
            override fun run() {
                // Skip if circuit breaker is open — don't even spawn a thread
                if (MaApiClient.isCircuitOpen()) {
                    Log.d(TAG, "🎵 Recently played: circuit open, retrying in 30s")
                    mainHandler.postDelayed(this, 30_000)
                    return
                }
                // Only one fetch thread at a time
                if (!recentlyPlayedFetchActive.compareAndSet(false, true)) {
                    Log.d(TAG, "🎵 Recently played: fetch already running, skipping")
                    mainHandler.postDelayed(this, 30_000)
                    return
                }
                val profileMgr = musicPlayer.musicProfileManager
                Thread {
                    try {
                        // Ensure MA user data is loaded before resolving user-scoped client
                        profileMgr?.ensureDataLoaded()
                        val apiClient = profileMgr?.getUserScopedClient()
                            ?: createMaApiClient(registry)
                            ?: run {
                                Log.w(TAG, "🎵 Recently played: no MA API client yet, retrying in 3s")
                                mainHandler.postDelayed(this, 3_000)
                                return@Thread
                            }
                        // Use categorized fetch for sectioned recents (Songs, Albums, Artists)
                        val ds = com.dashieapp.Dashie.halite.music.MediaBrowserDataSource(apiClient,
                            registry.prefs.connection.getEffectiveMaApiUrl())
                        val sections = ds.fetchRecentsCategorized()
                        val allItems = sections.flatMap { it.items }
                        if (allItems.isNotEmpty()) {
                            val conn = registry.prefs.connection
                            val needsAuth = allItems.any { it.imageUrl?.startsWith("http://192.168") == true || it.imageUrl?.contains("imageproxy") == true }
                            val data = if (needsAuth) {
                                com.dashieapp.Dashie.halite.music.RecentlyPlayedData(
                                    items = allItems, sections = sections,
                                    maApiToken = conn.maApiToken,
                                    maApiUrl = conn.getEffectiveMaApiUrl()
                                )
                            } else {
                                com.dashieapp.Dashie.halite.music.RecentlyPlayedData(items = allItems, sections = sections)
                            }
                            Log.i(TAG, "🎵 Recently played (categorized): ${sections.size} sections, ${allItems.size} items")
                            nativeRecentlyPlayedLoaded = true
                            mainHandler.post { musicPlayer.updateRecentlyPlayed(data) }
                            mainHandler.postDelayed(this, 30_000)
                            return@Thread
                        }
                        // Categorized returned empty — retry later
                        Log.w(TAG, "🎵 Recently played: categorized fetch empty, retrying in 30s")
                        mainHandler.postDelayed(this, 30_000)
                    } catch (e: Exception) {
                        Log.e(TAG, "🎵 Failed to fetch recently played from MA API: ${e.message}")
                        // If native fetch never succeeded, trigger JS injection as fallback
                        if (!nativeRecentlyPlayedLoaded) {
                            Log.i(TAG, "🎵 Recently played: MA API failed, triggering JS fallback")
                            mainHandler.post {
                                val webView = registry.webViewRef
                                if (webView != null) {
                                    if (registry.prefs.connection.hasAddonUrl) {
                                        com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                                            .injectRecentlyPlayedQueryViaShell(webView)
                                    } else {
                                        com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                                            .injectRecentlyPlayedQuery(webView)
                                    }
                                }
                            }
                        }
                        mainHandler.postDelayed(this, 60_000)
                    } finally {
                        recentlyPlayedFetchActive.set(false)
                    }
                }.start()
            }
        }
        mainHandler.post(fetchRunnable) // Fetch immediately
    }

    /**
     * Extract the best image URL from an MA API item JSON.
     * Routes through MA server's /imageproxy (or HA relay's /api/dashie/music/imageproxy
     * when in relay mode) so the tablet only needs LAN or proxy access.
     * The path must be double-URL-encoded for the imageproxy endpoint.
     *
     * @param imageProxyBase The base URL for image proxy requests. Either the MA API URL
     *   (direct mode: "{maUrl}/imageproxy?...") or HA URL (relay mode: "{haUrl}/api/dashie/music/imageproxy?...").
     */
    /** Public accessor for image resolution — used by HaliteComponentRegistry too. */
    fun resolveImageForRecentlyPlayed(obj: org.json.JSONObject, maApiUrl: String): String? =
        resolveImageFromMaItem(obj, maApiUrl)

    private fun resolveImageFromMaItem(obj: org.json.JSONObject, maApiUrl: String): String? {
        // Try top-level image field
        val image = obj.opt("image")
        if (image is String && image.isNotEmpty()) {
            return if (image.startsWith("http")) image else "$maApiUrl$image"
        }
        if (image is org.json.JSONObject) {
            val path = image.optString("path", "")
            val remotely = image.optBoolean("remotely_accessible", false)
            if (path.isNotEmpty()) {
                return if (remotely || path.startsWith("http")) path
                else "$maApiUrl/api/thumb?path=${java.net.URLEncoder.encode(path, "UTF-8")}&size=256"
            }
        }
        // Try image_url (some MA versions)
        val imageUrl = obj.optString("image_url", "")
        if (imageUrl.isNotEmpty()) {
            return if (imageUrl.startsWith("http")) imageUrl else "$maApiUrl$imageUrl"
        }
        // Try metadata.images array
        val metadata = obj.optJSONObject("metadata")
        val images = metadata?.optJSONArray("images")
        if (images != null && images.length() > 0) {
            for (i in 0 until images.length()) {
                val img = images.getJSONObject(i)
                if (img.optString("type") == "thumb") {
                    val path = img.optString("path", "")
                    val remotely = img.optBoolean("remotely_accessible", false)
                    if (path.isNotEmpty()) {
                        return if (remotely || path.startsWith("http")) path
                        else "$maApiUrl/api/thumb?path=${java.net.URLEncoder.encode(path, "UTF-8")}&size=256"
                    }
                }
            }
            val first = images.getJSONObject(0)
            val path = first.optString("path", "")
            val remotely = first.optBoolean("remotely_accessible", false)
            if (path.isNotEmpty()) {
                return if (remotely || path.startsWith("http")) path
                else "$maApiUrl/api/thumb?path=${java.net.URLEncoder.encode(path, "UTF-8")}&size=256"
            }
        }
        return null
    }

}

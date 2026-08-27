package com.dashieapp.Dashie.halite.music

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Polls Music Assistant REST API for player state.
 *
 * When a real-time source (Sendspin) is connected, the poller provides
 * position, duration, and track metadata to [MusicSourceCallbacks].
 * When no real-time source is connected, the poller also provides play state.
 *
 * Polls every 1s when playing (for smooth position interpolation), 5s when idle.
 */
class MaPollerService(
    private val callbacks: MusicSourceCallbacks,
    private val apiClientProvider: () -> MaApiClient?,
    private val entityProvider: () -> String?,
    private val streamsServerProvider: () -> String?,
    private val imageUrlResolver: (String) -> String?,
    private val volumeProvider: () -> Float,
    private val isRealtimeSourceConnected: () -> Boolean,
    private val onEntityNotFound: () -> Unit
) {
    companion object {
        private const val TAG = "MaPoller"
        // When the MA token is dead, every poll 401s. A 401 is a valid HTTP
        // response, so it does NOT open the network circuit breaker — without
        // this the poller re-hammers players/all every 3s indefinitely. Back
        // off hard on auth failure: the 60s-throttled central-store re-check
        // (handleMaAuthFailure) still recovers when a fresh token appears, and
        // polling snaps back to normal the moment a call succeeds.
        private const val AUTH_FAIL_BACKOFF_MS = 20_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    @Volatile var isActive: Boolean = false
        private set
    @Volatile var lastPollAt: Long = 0
        private set
    @Volatile var forceRefresh: Boolean = false

    /**
     * Called when the poller detects this device is playing as a group member
     * (player state=playing but own queue=idle). Triggers entity re-resolution
     * so the poller switches to the group entity.
     */
    var onGroupMembershipDetected: (() -> Unit)? = null
    @Volatile private var groupResolutionRequested: Boolean = false

    /**
     * Called when the polled entity transitions from playing to idle.
     * The device may now be in a different playing group — triggers re-resolution.
     */
    var onTargetWentIdle: (() -> Unit)? = null
    @Volatile private var lastPolledWasPlaying: Boolean = false
    private val pollThreadActive = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (pollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (forceRefresh) {
                    forceRefresh = false
                    Log.i(TAG, "🎵 Forced refresh — position tracking reset")
                }

                // Skip if circuit breaker is open (MA unreachable) — back off longer
                if (MaApiClient.isCircuitOpen()) {
                    isActive = false
                    mainHandler.postDelayed(this, 10_000)
                    return
                }

                val maEntity = entityProvider()
                val apiClient = apiClientProvider()
                val maServer = streamsServerProvider()

                // Need either API client or streams server
                if (apiClient == null && maServer == null) {
                    isActive = false
                    mainHandler.postDelayed(this, 3000)
                    return
                }

                // Only one poll thread at a time — skip if previous is still running
                if (!pollThreadActive.compareAndSet(false, true)) {
                    mainHandler.postDelayed(this, 3000)
                    return
                }
                Thread {
                    try {
                        val obj = if (apiClient != null) {
                            pollViaApi(apiClient, maEntity)
                        } else {
                            pollViaStreamsProxy(maServer!!, maEntity)
                        }

                        if (obj == null || obj.has("error")) {
                            // A 401 (dead token) returns null but keeps the circuit
                            // closed — back off hard instead of re-polling every 3s.
                            val delay = if (apiClient?.authFailed == true) {
                                Log.d(TAG, "🎵 Poll auth-failed (401) — backing off ${AUTH_FAIL_BACKOFF_MS}ms")
                                AUTH_FAIL_BACKOFF_MS
                            } else 3000L
                            mainHandler.postDelayed(this, delay)
                            return@Thread
                        }

                        val parsed = parsePlayerState(obj, maEntity)
                        if (parsed == null) {
                            mainHandler.postDelayed(this, 3000)
                            return@Thread
                        }

                        val isPlaying = parsed.state == "playing"
                        val isPaused = parsed.state == "paused"

                        if (parsed.track.isEmpty() && !isPlaying && !isPaused) {
                            Log.d(TAG, "🎵 [POLLER] idle: entity=${parsed.queueId} name=${parsed.playerName} dur=${parsed.duration}")
                            // Detect playing→idle transition: device may now be in a different group
                            if (lastPolledWasPlaying) {
                                lastPolledWasPlaying = false
                                Log.i(TAG, "🎵 Target went idle (was playing) — requesting entity re-resolution")
                                mainHandler.post { onTargetWentIdle?.invoke() }
                            }
                            callbacks.onPollerIdle(parsed.queueId, parsed.playerName, parsed.vol)
                            isActive = true
                            lastPollAt = System.currentTimeMillis()
                            mainHandler.postDelayed(this, 5000)
                            return@Thread
                        }

                        // Push track data to coordinator
                        val serverElapsed = if (parsed.elapsedTime >= 0) parsed.elapsedTime else -1.0

                        // Interpolate position using elapsed_time_last_updated
                        val duration = parsed.duration
                        val displayPosition: Double = if (serverElapsed >= 0) {
                            val lastUpdated = obj.optDouble("elapsed_time_last_updated", -1.0)
                            val interpolated = if (isPlaying && lastUpdated > 0) {
                                val deltaSec = (System.currentTimeMillis() / 1000.0 - lastUpdated).coerceAtLeast(0.0)
                                serverElapsed + deltaSec
                            } else {
                                serverElapsed
                            }
                            // Clamp to track duration — individual players that joined a group
                            // mid-song can report cumulative/wrong elapsed_time from MA
                            if (duration > 0) interpolated.coerceAtMost(duration.toDouble()) else interpolated
                        } else {
                            -1.0
                        }

                        if (isPlaying) lastPolledWasPlaying = true

                        callbacks.onPollerTrackUpdate(
                            track = parsed.track,
                            artist = parsed.artist,
                            album = parsed.album,
                            artworkUrl = parsed.imageUrl,
                            positionSec = displayPosition,
                            durationSec = parsed.duration,
                            isPlaying = isPlaying,
                            entityId = parsed.queueId,
                            friendlyName = parsed.playerName,
                            shuffleEnabled = parsed.shuffleEnabled,
                            repeatMode = parsed.repeatMode,
                            // Stale track present but MA is neither playing nor
                            // paused → stopped/idle. Marks the card as not a
                            // genuinely-displayed player for the screensaver.
                            isStopped = !isPlaying && !isPaused
                        )

                        isActive = true
                        lastPollAt = System.currentTimeMillis()
                        val nextDelay = if (isPlaying) 1000L else if (isPaused) 3000L else 5000L
                        mainHandler.postDelayed(this, nextDelay)
                    } catch (e: Exception) {
                        Log.w(TAG, "🎵 Poll failed: ${e.message}")
                        mainHandler.postDelayed(this, 5000)
                    } finally {
                        pollThreadActive.set(false)
                    }
                }.start()
            }
        }
        pollRunnable = runnable
        mainHandler.postDelayed(runnable, 2000)
        Log.i(TAG, "🎵 Poller started")
    }

    fun stop() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
        isActive = false
    }

    // ── API polling ──

    private fun pollViaApi(apiClient: MaApiClient, maEntity: String?): org.json.JSONObject? {
        if (maEntity != null) {
            val result = apiClient.getPlayerState(maEntity)
            if (result != null) {
                val queue = apiClient.getQueueState(maEntity)
                if (queue != null) {
                    val queueElapsed = if (queue.isNull("elapsed_time")) -1.0 else queue.optDouble("elapsed_time", -1.0)
                    val queueElapsedUpdated = if (queue.isNull("elapsed_time_last_updated")) -1.0 else queue.optDouble("elapsed_time_last_updated", -1.0)
                    val queueState = queue.optString("state", "")
                    // Sync groups always return elapsed_time=0 from MA.
                    // Pass -1 so the coordinator keeps interpolating from its own baseline
                    // instead of resetting to 0 every poll cycle.
                    val effectiveElapsed = if (queueElapsed <= 0.0 && queueState == "playing" && maEntity.startsWith("syncgroup_")) -1.0 else queueElapsed
                    if (effectiveElapsed >= 0) result.put("elapsed_time", effectiveElapsed)
                    if (queueElapsedUpdated > 0) result.put("elapsed_time_last_updated", queueElapsedUpdated)
                    if (queueState.isNotEmpty() && queueState != "idle") result.put("playback_state", queueState)
                    // Forward queue-level shuffle/repeat onto the merged result so
                    // parsePlayerState() can read them in one place.
                    if (queue.has("shuffle_enabled")) result.put("shuffle_enabled", queue.optBoolean("shuffle_enabled", false))
                    if (queue.has("repeat_mode")) result.put("repeat_mode", queue.optString("repeat_mode", "off"))
                    Log.d(TAG, "🎵 API poll: entity=$maEntity pState=${result.optString("playback_state", "?")} qState=$queueState qElapsed=$effectiveElapsed")

                    // Detect group membership: player is playing but own queue is idle.
                    // This means the device is playing as part of a sync group.
                    // Trigger entity re-resolution to switch poller to the group entity.
                    val playerState = result.optString("playback_state", "idle")
                    if (playerState == "playing" && queueState == "idle" && !groupResolutionRequested) {
                        groupResolutionRequested = true
                        Log.i(TAG, "🎵 Group membership detected (playing but queue idle) — requesting entity resolution")
                        mainHandler.post { onGroupMembershipDetected?.invoke() }
                    } else if (playerState != "playing" || queueState != "idle") {
                        groupResolutionRequested = false
                    }
                }
                return result
            }
            Log.d(TAG, "🎵 API: getPlayerState($maEntity) failed — falling back to players/all")
        }
        val players = apiClient.getPlayers() ?: return null
        if (players.length() == 0) return null
        if (maEntity != null) {
            for (i in 0 until players.length()) {
                val p = players.getJSONObject(i)
                if (p.optString("player_id") == maEntity) return p
            }
        }
        if (maEntity == null) return players.getJSONObject(0)
        return null
    }

    private fun pollViaStreamsProxy(maServer: String, maEntity: String?): org.json.JSONObject? {
        var json = ""
        var gotSpecific = false
        if (maEntity != null) {
            val conn = java.net.URL("$maServer/player_state/$maEntity").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            val code = conn.responseCode
            if (code == 200) { json = conn.inputStream.bufferedReader().readText(); conn.disconnect(); gotSpecific = true }
            else {
                conn.disconnect()
                if (code == 404) {
                    Log.w(TAG, "🎵 Entity $maEntity not found (404)")
                    onEntityNotFound()
                }
            }
        }
        if (!gotSpecific) {
            val conn = java.net.URL("$maServer/player_state").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            json = conn.inputStream.bufferedReader().readText(); conn.disconnect()
        }
        return if (json.isNotEmpty()) org.json.JSONObject(json) else null
    }

    // ── State parsing ──

    data class PlayerStateParsed(
        val state: String, val track: String, val artist: String, val album: String,
        val imageUrl: String?, val elapsedTime: Double, val duration: Int,
        val queueId: String, val playerName: String, val vol: Float,
        val shuffleEnabled: Boolean = false,
        /** "off" | "all" | "one" — empty if not reported. */
        val repeatMode: String = "off"
    )

    private fun parsePlayerState(obj: org.json.JSONObject, maEntity: String?): PlayerStateParsed? {
        val state = obj.optString("playback_state", obj.optString("state", "idle"))

        val currentMedia = obj.optJSONObject("current_media")
        val track: String
        val artist: String
        val album: String
        val imageUrl: String?
        val duration: Int
        if (currentMedia != null) {
            track = currentMedia.optString("title", "")
            artist = currentMedia.optString("artist", "")
            album = currentMedia.optString("album", "")
            val imgUrl = currentMedia.optString("image_url", "")
            imageUrl = if (imgUrl.isNotEmpty()) {
                if (imgUrl.startsWith("http")) imgUrl
                else imageUrlResolver(imgUrl)
            } else null
            duration = currentMedia.optInt("duration", 0)
        } else {
            track = obj.optString("track", "")
            artist = obj.optString("artist", "")
            album = obj.optString("album", "")
            imageUrl = obj.optString("image_url", "").takeIf { it.isNotEmpty() }
            duration = obj.optInt("duration", 0)
        }

        val elapsedTime = if (obj.isNull("elapsed_time")) -1.0 else obj.optDouble("elapsed_time", -1.0)
        val queueId = obj.optString("player_id", obj.optString("queue_id", maEntity ?: "unknown"))
        val rawDisplayName = obj.optString("display_name", obj.optString("player_name", ""))
        val model = obj.optJSONObject("device_info")?.optString("model", "") ?: ""
        val playerName = when {
            rawDisplayName.isNotEmpty() && rawDisplayName != model -> rawDisplayName
            else -> {
                val playerId = obj.optString("player_id", "")
                if (playerId.startsWith("media_player.")) {
                    playerId.removePrefix("media_player.")
                        .replace("_", " ")
                        .replaceFirstChar { it.uppercase() }
                        .replace(Regex("\\b\\w")) { it.value.uppercase() }
                } else {
                    rawDisplayName.ifEmpty { "Music Assistant" }
                }
            }
        }

        val vol = volumeProvider()

        val shuffleEnabled = obj.optBoolean("shuffle_enabled", false)
        val repeatMode = obj.optString("repeat_mode", "off").ifEmpty { "off" }

        return PlayerStateParsed(
            state, track, artist, album, imageUrl, elapsedTime, duration,
            queueId, playerName, vol, shuffleEnabled, repeatMode
        )
    }
}

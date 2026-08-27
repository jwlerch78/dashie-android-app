package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.WebView
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delegate handling music player overlay JavaScript bridge methods.
 * Manages bidirectional communication between Kotlin music player overlay
 * and JavaScript (injected into HA WebView).
 *
 * Architecture:
 * - State updates come from the injected JS in HA WebView (via hass.states subscription)
 * - Playback commands go to the injected JS via CustomEvents (uses hass.callService)
 * - Retry/bounce detection logic is in the injected JS
 * - Voice search play commands use HA REST API directly (bypasses iframe chain)
 *
 * JavaScript → Kotlin: Music state updates (updateMusicPlayer, hidePlayer)
 * Kotlin → JavaScript: Play/pause/next/previous via CustomEvents dispatched to HA WebView
 */
class JsBridgeMusicDelegate(
    private val webView: WebView
) {
    /**
     * Provider for HA connection info needed for direct REST API calls.
     * Set by MainActivity when wiring the JS bridge.
     */
    data class HaConnectionInfo(
        val haUrl: String,
        val haToken: String,
        val musicPlayerEntityId: String
    )
    var haConnectionProvider: (() -> HaConnectionInfo?)? = null
    companion object {
        private const val TAG = "JsBridgeMusic"
    }

    // Callbacks for music events (JavaScript → Kotlin)
    var onMusicPlayerUpdate: ((String) -> Unit)? = null
    var onMusicPlayerHide: (() -> Unit)? = null
    var onMusicPlayerEntityNotFound: ((String) -> Unit)? = null
    var onRecentlyPlayedUpdate: ((String) -> Unit)? = null

    /**
     * Callback for voice music commands routed through MA REST API.
     * When set, sendMusicCommand routes through this instead of HA iframe CustomEvents.
     * Parameters: (command: String, paramsJson: String)
     */
    var onVoiceMusicCommand: ((String, String) -> Unit)? = null

    // ============================================
    // Music State Updates (JavaScript → Kotlin)
    // Called from JavaScript when music state changes
    // ============================================

    /**
     * Called from JavaScript when music player state should be updated.
     * @param musicJson JSON string with music player data:
     *   - trackName: String
     *   - artistName: String
     *   - albumName: String
     *   - albumArtUrl: String?
     *   - isPlaying: Boolean
     *   - positionSeconds: Int
     *   - durationSeconds: Int
     */
    fun updateMusicPlayer(musicJson: String) {
        webView.post {
            if (onMusicPlayerUpdate != null) {
                onMusicPlayerUpdate?.invoke(musicJson)
            } else {
                Log.e(TAG, "🎵 ERROR: onMusicPlayerUpdate callback is NULL - wiring incomplete!")
            }
        }
    }

    /**
     * Called from JavaScript to hide the music player.
     * Used when media player goes idle/off.
     */
    fun hideMusicPlayer() {
        Log.d(TAG, "🎵 hideMusicPlayer() called")
        webView.post {
            onMusicPlayerHide?.invoke()
        }
    }

    /**
     * Called from JavaScript when the configured music player entity is not found in HA states.
     */
    fun onMusicPlayerEntityNotFound(entityId: String) {
        Log.w(TAG, "🎵 Entity not found in HA states: $entityId")
        webView.post {
            onMusicPlayerEntityNotFound?.invoke(entityId)
        }
    }

    /**
     * Called from JavaScript with recently played items data.
     */
    fun updateRecentlyPlayed(json: String) {
        Log.d(TAG, "🎵 Recently played update received (${json.length} bytes)")
        webView.post {
            onRecentlyPlayedUpdate?.invoke(json)
        }
    }

    /**
     * Run one AI `music` tool call (now_playing | search | play) and return its result
     * JSON synchronously. The cascade JS lane (music-tool.js) calls this via
     * DashieNative.musicToolQuery; Gemini Live reaches the same executor through
     * RealtimeToolDispatcher. Runs on the WebView's JavaBridge thread, so blocking on
     * the MA REST call is fine (it only stalls this call, not the page).
     */
    fun musicToolQuery(argsJson: String): String {
        val tool = com.dashieapp.Dashie.halite.music.MusicVoiceTool.instance
            ?: return """{"found":false,"error":"music not available on this device"}"""
        val args = try {
            org.json.JSONObject(argsJson)
        } catch (_: Exception) {
            return """{"found":false,"error":"bad args"}"""
        }
        return tool.execute(args).toString()
    }

    /**
     * Play a recently played item by URI via the existing play_media event.
     * The URI (e.g. "library://album/15") is sent as mediaId.
     */
    fun sendPlayRecentItem(uri: String) {
        Log.i(TAG, "🎵 Playing recently played item: $uri")
        dispatchMusicEventWithDetail("music-player-play-media", "{ mediaId: '$uri' }")
    }

    // ============================================
    // Music Commands (Kotlin → JavaScript)
    // Called from Kotlin music card UI to control playback
    // Commands go to injected JS via CustomEvents - injected JS uses hass.callService
    // Retry/bounce detection logic is handled in the injected JS
    // ============================================

    /**
     * Send play/pause toggle command to the injected JS.
     * The injected JS has retry logic to handle Music Assistant's idle-bounce behavior.
     */
    fun sendPlayPause() {
        Log.i(TAG, "🎵 Sending play/pause to injected JS")
        dispatchMusicEvent("music-player-play-pause")
    }

    /**
     * Send next track command to the injected JS.
     */
    fun sendNext() {
        Log.i(TAG, "🎵 Sending next track to injected JS")
        dispatchMusicEvent("music-player-next")
    }

    /**
     * Send previous track command to the injected JS.
     */
    fun sendPrevious() {
        Log.i(TAG, "🎵 Sending previous track to injected JS")
        dispatchMusicEvent("music-player-previous")
    }

    /**
     * Send stop command to the injected JS.
     * Stops playback and resets session state so player stays hidden.
     */
    fun sendStop() {
        Log.i(TAG, "🎵 Sending stop to injected JS")
        dispatchMusicEvent("music-player-stop")
    }

    /**
     * Notify JavaScript that minimize state was toggled.
     */
    fun sendToggleMinimize() {
        Log.i(TAG, "🎵 Sending toggle minimize to injected JS")
        dispatchMusicEvent("music-player-toggle-minimize")
    }

    /**
     * Dispatch a CustomEvent to the HA iframe for the injected JS to handle.
     * Routes through evalInHaIframe() when available (kiosk shell or full/linked mode)
     * so the event reaches listeners inside the HA iframe, not the top-level window.
     * Falls back to direct dispatch for legacy mode (HA as top-level page).
     */
    private fun dispatchMusicEvent(eventName: String) {
        webView.post {
            webView.evaluateJavascript(
                "if(typeof evalInHaIframe==='function'){evalInHaIframe(\"window.dispatchEvent(new CustomEvent('$eventName'));\");}else{window.dispatchEvent(new CustomEvent('$eventName'));}",
                null
            )
        }
    }

    /**
     * Dispatch a CustomEvent with detail payload to the HA iframe.
     * Routes through evalInHaIframe() when available, falls back to direct dispatch.
     */
    private fun dispatchMusicEventWithDetail(eventName: String, detail: String) {
        // Escape detail for nested string context inside evalInHaIframe
        val escapedDetail = detail.replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "if(typeof evalInHaIframe==='function'){evalInHaIframe(\"window.dispatchEvent(new CustomEvent('$eventName',{detail:$escapedDetail}));\");}else{window.dispatchEvent(new CustomEvent('$eventName',{detail:$detail}));}",
                null
            )
        }
    }

    // ============================================
    // HA REST API - Direct Music Service Calls
    // ============================================

    /**
     * Call HA's music_assistant.play_media service directly via REST API.
     * Bypasses the iframe CustomEvent chain for reliable playback.
     *
     * HA's music_assistant.play_media service accepts search strings as media_id
     * and handles search + play in one atomic call. The service uses the entity's
     * default player if no specific entity is provided, or we can target a specific
     * MA-managed entity.
     */
    private fun callHaPlayMedia(haInfo: HaConnectionInfo, mediaId: String) {
        Thread {
            try {
                val url = URL("${haInfo.haUrl.trimEnd('/')}/api/services/music_assistant/play_media")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer ${haInfo.haToken}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true

                val body = org.json.JSONObject().apply {
                    put("media_id", mediaId)
                    // Include entity_id if we have a valid media_player.* entity
                    val entityId = haInfo.musicPlayerEntityId
                    if (entityId.startsWith("media_player.")) {
                        // MA's play_media requires the _2 (MA-managed) entity
                        val maEntity = if (entityId.endsWith("_2")) entityId else "${entityId}_2"
                        put("entity_id", maEntity)
                    }
                    // If no valid entity, HA will use the default/first available
                }

                Log.i(TAG, "🎵 HA REST: POST play_media body=${body}")

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    Log.i(TAG, "🎵 HA play_media success: '$mediaId' (code=$responseCode)")
                } else {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    Log.e(TAG, "🎵 HA play_media failed ($responseCode): $errorBody")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "🎵 HA play_media error: ${e.message}", e)
            }
        }.start()
    }

    // ============================================
    // Volume Commands (Kotlin → JavaScript)
    // ============================================

    /**
     * Set entity volume level via HA service.
     * @param volumeLevel Volume level from 0.0 to 1.0
     */
    fun sendVolumeSet(volumeLevel: Float) {
        Log.i(TAG, "🎵 Sending volume set: $volumeLevel")
        dispatchMusicEventWithDetail("music-player-volume-set", "{ volumeLevel: $volumeLevel }")
    }

    /**
     * Switch the active music player entity in the injected JS.
     * Used by the entity picker to change which speaker the card shows/controls.
     */
    fun sendSwitchEntity(entityId: String) {
        Log.i(TAG, "🎵 Sending switch entity: $entityId")
        dispatchMusicEventWithDetail("music-player-switch-entity", "{ entityId: '$entityId' }")
    }

    // ============================================
    // Music Assistant Commands (Voice → Kotlin → JS)
    // Uses music_assistant.play_media service for search/play
    // ============================================

    /**
     * Send play media command with search query.
     * Uses Music Assistant's play_media service which auto-detects media type.
     *
     * @param mediaId The search query (artist name, song title, album, etc.)
     * @param artist Optional artist filter to narrow search results
     */
    fun sendPlayMedia(mediaId: String, artist: String? = null) {
        Log.i(TAG, "🎵 Sending play media: query='$mediaId', artist=$artist")
        val detail = if (artist != null) {
            "{ mediaId: '$mediaId', artist: '$artist' }"
        } else {
            "{ mediaId: '$mediaId' }"
        }
        dispatchMusicEventWithDetail("music-player-play-media", detail)
    }

    /**
     * Generic music command handler called from JavaScript overlay.
     * Routes commands to appropriate methods.
     *
     * @param command The command type: play_media, play, pause, next, previous
     * @param paramsJson JSON string with command parameters
     */
    fun sendMusicCommand(command: String, paramsJson: String) {
        Log.i(TAG, "🎵 Voice command: $command, params: $paramsJson")
        try {
            val apiCallback = onVoiceMusicCommand

            // play_media with search strings: route through MA API (search + play)
            // MA API callback handles searchAndPlay on the tablet's configured speaker
            val isSearchPlay = command == "play_media" && !paramsJson.contains("://")
            if (isSearchPlay && apiCallback != null) {
                Log.i(TAG, "🎵 play_media search → MA API searchAndPlay")
                apiCallback(command, paramsJson)
                return
            }

            // Other commands: prefer MA REST API when wired
            if (apiCallback != null) {
                Log.i(TAG, "🎵 Routing '$command' via MA API callback")
                apiCallback(command, paramsJson)
                return
            }

            // Fallback: dispatch CustomEvents to HA iframe
            Log.i(TAG, "🎵 Routing '$command' via HA iframe CustomEvents (fallback)")
            when (command) {
                "play_media" -> {
                    val params = org.json.JSONObject(paramsJson)
                    val mediaId = params.getString("mediaId")
                    val artist = params.optString("artist", "").takeIf { it.isNotEmpty() }
                    sendPlayMedia(mediaId, artist)
                }
                "play" -> sendPlay()
                "pause" -> sendPause()
                "stop" -> sendStop()
                "play_pause" -> sendPlayPause()
                "next" -> sendNext()
                "previous" -> sendPrevious()
                "volume_up" -> {
                    val params = org.json.JSONObject(paramsJson)
                    val delta = params.optInt("delta", 10)
                    dispatchMusicEventWithDetail("music-player-volume-delta", "{ delta: $delta }")
                }
                "volume_down" -> {
                    val params = org.json.JSONObject(paramsJson)
                    val delta = params.optInt("delta", 10)
                    dispatchMusicEventWithDetail("music-player-volume-delta", "{ delta: -$delta }")
                }
                else -> Log.w(TAG, "🎵 Unknown music command: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🎵 Error processing music command: ${e.message}", e)
        }
    }

    /**
     * Send explicit play command (not toggle).
     */
    fun sendPlay() {
        Log.i(TAG, "🎵 Sending play to injected JS")
        dispatchMusicEvent("music-player-play")
    }

    /**
     * Send explicit pause command (not toggle).
     */
    fun sendPause() {
        Log.i(TAG, "🎵 Sending pause to injected JS")
        dispatchMusicEvent("music-player-pause")
    }

    /**
     * Show music player with mock data for testing.
     * Called from developer settings or diagnostics.
     */
    fun showMockPlayer() {
        Log.i(TAG, "Showing mock music player for testing")
        val mockJson = """
            {
                "trackName": "Test Track Name",
                "artistName": "Test Artist",
                "albumName": "Test Album",
                "albumArtUrl": null,
                "isPlaying": true,
                "positionSeconds": 45,
                "durationSeconds": 253
            }
        """.trimIndent()
        onMusicPlayerUpdate?.invoke(mockJson)
    }

    /**
     * Toggle mock player playing state for testing.
     */
    fun toggleMockPlayState() {
        Log.i(TAG, "Toggle mock play state requested")
        // This is handled by the callback - just for logging/testing
    }

}

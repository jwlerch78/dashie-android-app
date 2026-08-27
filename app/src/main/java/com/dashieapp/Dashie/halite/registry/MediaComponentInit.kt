package com.dashieapp.Dashie.halite.registry

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HaliteComponentWiring
import com.dashieapp.Dashie.halite.WebViewBottomInsetManager

private const val TAG = "HaliteRegistry"

// ============================================================
// Media / Music / Video / Photo / Weather initialization
// extracted from HaliteComponentRegistry
// ============================================================

internal fun HaliteComponentRegistry.initializeMusicPlayer() {
    // Speaker-only mode: no player UI, just Sendspin receiver
    if (prefs.connection.musicSpeakerOnly) {
        Log.d(TAG, "🔊 Speaker-only mode — skipping music player UI init")
        return
    }

    val startTime = System.currentTimeMillis()

    // Get overlay container from activity layout (same as timer/performance overlays)
    val overlayContainer = activityRef.findViewById<android.widget.FrameLayout>(com.dashieapp.Dashie.R.id.overlayContainer)
    if (overlayContainer == null) {
        Log.e(TAG, "❌ Could not find overlayContainer for music player!")
        return
    }

    // Create bottom inset manager (shared across all overlay bars)
    if (bottomInsetManager == null) {
        bottomInsetManager = WebViewBottomInsetManager(activityRef, webViewProvider)
    }

    musicPlayerManager = com.dashieapp.Dashie.halite.music.MusicPlayerOverlayManager(
        context = activityRef,
        overlayContainer = overlayContainer,
        onToggleMinimized = {
            jsBridge?.sendMusicToggleMinimize()
        },
        visibilityGate = nativeWidgetVisibilityGate
    ).also {
        it.fullScreenOnPlay = prefs.connection.musicPlayerFullScreenOnPlay
        it.musicProfileManager = com.dashieapp.Dashie.halite.music.MusicProfileManager(prefs.connection)
    }

    // Load family members into music profile manager via JS bridge
    loadFamilyMembersForMusicProfile()

    // Wire up play/pause callback to communicate back to WebView
    musicPlayerManager?.onPlayPauseClicked = {
        Log.i(TAG, "🎵 Music play/pause clicked - dispatching CustomEvent to WebView")
        webViewRef.post {
            val js = "console.log('[DashieMusic] 🎵 CustomEvent dispatch from Kotlin'); " +
                "window.dispatchEvent(new CustomEvent('music-player-play-pause'));"
            Log.d(TAG, "🎵 Evaluating JS: $js")
            webViewRef.evaluateJavascript(js, null)
        }
    }

    // Persist visibility state to prefs on show/hide
    musicPlayerManager?.onSaveVisibility = { shown ->
        prefs.connection.musicPlayerWasShown = shown
        Log.d(TAG, "🎵 Saved musicPlayerWasShown=$shown")
    }

    // Notify sidebar to refresh toggle button text when visibility changes
    musicPlayerManager?.onVisibilityChanged = { _ ->
        // Music toggle refresh was on the legacy sidebar — no-op now
    }

    // Update WebView bottom padding when music player covers the bottom edge
    musicPlayerManager?.onBottomInsetDp = { dp ->
        Log.i(TAG, "🎵 Bottom inset callback: ${dp}dp, bottomInsetManager=${bottomInsetManager != null}")
        if (dp > 0) {
            bottomInsetManager?.setInset("music_player", dp)
        } else {
            bottomInsetManager?.removeInset("music_player")
        }
    }

    // Persist position on drag end
    musicPlayerManager?.onSavePosition = { isMinimized, x, y ->
        if (isMinimized) {
            prefs.connection.musicPlayerMiniPos = "$x,$y"
        } else {
            prefs.connection.musicPlayerNormalPos = "$x,$y"
        }
        Log.d(TAG, "🎵 Saved ${if (isMinimized) "mini" else "normal"} position ($x, $y)")
    }

    // Load saved positions from prefs
    musicPlayerManager?.onLoadPositions = {
        val normalPos = prefs.connection.musicPlayerNormalPos.ifEmpty { null }
        val miniPos = prefs.connection.musicPlayerMiniPos.ifEmpty { null }
        Pair(normalPos, miniPos)
    }

    // Cache track data for next startup
    musicPlayerManager?.onSaveTrackData = { track, artist, albumArtUrl ->
        prefs.connection.musicPlayerLastTrack = track
        prefs.connection.musicPlayerLastArtist = artist
        prefs.connection.musicPlayerLastAlbumArt = albumArtUrl ?: ""
        Log.d(TAG, "🎵 Cached track: $track by $artist")
    }

    // Load cached track data
    musicPlayerManager?.onLoadTrackData = {
        val track = prefs.connection.musicPlayerLastTrack
        val artist = prefs.connection.musicPlayerLastArtist
        val art = prefs.connection.musicPlayerLastAlbumArt.ifEmpty { null }
        if (track.isNotBlank()) Triple(track, artist, art) else null
    }

    // Polling callback: re-inject JS subscription to check for Music Assistant
    musicPlayerManager?.onRequestReInject = {
        val entityId = prefs.connection.getEffectiveMusicPlayerEntityId()
        if (entityId.isNotEmpty()) {
            if (prefs.connection.hasAddonUrl) {
                // Shell/kiosk mode: HA is in an iframe, relay JS through shell page
                val escaped = "window._dashieMusicUserStopped = false;".replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")
                webViewRef.evaluateJavascript("evalInHaIframe(`${escaped}`);", null)
                com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                    .injectMusicPlayerSubscriptionViaShell(webViewRef, entityId, force = true)
            } else {
                webViewRef.evaluateJavascript("window._dashieMusicUserStopped = false;", null)
                com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                    .injectMusicPlayerSubscription(webViewRef, entityId, force = true)
            }
        }
    }

    // Recently played: prefer MA API, fallback to streams proxy, then JS
    musicPlayerManager?.onRequestRecentlyPlayed = {
        // Check if MA token is expired — prompt login on this user interaction
        if (HaliteComponentWiring.showMaLoginIfExpired(this)) {
            Log.i(TAG, "🎵 Recently played: MA token expired, login prompted")
        }
        val apiUrl = prefs.connection.getEffectiveMaApiUrl()
        val apiToken = prefs.connection.maApiToken
        val haBase = (prefs.connection.haBaseUrl.takeIf { it.isNotEmpty() } ?: prefs.connection.haUrl).trimEnd('/')
        val haToken = prefs.connection.haAccessToken
        if (apiUrl.isNotEmpty() && apiToken.isNotEmpty()) {
            Log.i(TAG, "🎵 Fetching recently played from MA API")
            Thread {
                try {
                    // Use user-scoped client if a profile is active
                    val profileMgr = musicPlayerManager?.musicProfileManager
                    profileMgr?.ensureDataLoaded()
                    val apiClient = profileMgr?.getUserScopedClient()
                        ?: com.dashieapp.Dashie.halite.music.MaApiClient(
                            baseUrl = apiUrl,
                            token = apiToken
                        )
                    // Fetch categorized recents directly from API
                    val ds = com.dashieapp.Dashie.halite.music.MediaBrowserDataSource(apiClient, apiUrl)
                    val sections = ds.fetchRecentsCategorized()
                    val allItems = sections.flatMap { it.items }
                    Log.i(TAG, "🎵 MA API recently played: ${sections.size} sections, ${allItems.size} items")
                    if (allItems.isNotEmpty()) {
                        val data = com.dashieapp.Dashie.halite.music.RecentlyPlayedData(
                            items = allItems,
                            sections = sections
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            musicPlayerManager?.updateRecentlyPlayed(data)
                        }
                    } else {
                        Log.w(TAG, "🎵 MA API recently played: empty")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Failed to fetch recently played from MA API: ${e.message}")
                }
            }.start()
        } else {
        val useHa = prefs.connection.musicUseHaIntegration
        val maStreamsUrl = if (useHa) null
            else dashieServiceManager?.audioManager?.maServerUrl?.takeIf { it.isNotEmpty() }
                ?: try {
                    val haUrl = java.net.URL(prefs.connection.haUrl)
                    "${haUrl.protocol}://${haUrl.host}:8097"
                } catch (_: Exception) { null }
        if (maStreamsUrl != null) {
            // Legacy: direct fetch from MA streams server (no auth needed)
            Log.i(TAG, "🎵 Fetching recently played from MA streams: $maStreamsUrl")
            Thread {
                try {
                    val url = java.net.URL("$maStreamsUrl/recently_played?limit=10")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    Log.i(TAG, "🎵 Recently played response: ${json.length} bytes")
                    val arr = org.json.JSONArray(json)
                    val items = (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        val imgUrl = obj.optString("image_url", "").takeIf { it.isNotEmpty() }
                        com.dashieapp.Dashie.halite.music.RecentlyPlayedItem(
                            name = obj.optString("name", ""),
                            uri = obj.optString("uri", ""),
                            mediaType = obj.optString("media_type", "album"),
                            imageUrl = imgUrl,
                            artist = null
                        )
                    }
                    val data = com.dashieapp.Dashie.halite.music.RecentlyPlayedData(items, maServerUrl = maStreamsUrl)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        musicPlayerManager?.updateRecentlyPlayed(data)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Failed to fetch recently played from MA: ${e.message}")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (prefs.connection.hasAddonUrl) {
                            com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                                .injectRecentlyPlayedQueryViaShell(webViewRef)
                        } else {
                            com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                                .injectRecentlyPlayedQuery(webViewRef)
                        }
                    }
                }
            }.start()
        } else {
            // No MA streams URL — use JS approach via HA WebSocket
            if (prefs.connection.hasAddonUrl) {
                com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                    .injectRecentlyPlayedQueryViaShell(webViewRef)
            } else {
                com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
                    .injectRecentlyPlayedQuery(webViewRef)
            }
        }
        } // end else (legacy/JS fallback)
    }

    // Wire JS bridge callbacks if jsBridge is already set
    if (jsBridge != null) {
        Log.i(TAG, "🎵 jsBridge already set, wiring music player callbacks")
        HaliteComponentWiring.wireMusicPlayerCallbacks(this)
    }

    // Don't auto-restore — player only opens via sidebar Music button
    if (prefs.connection.musicPlayerWasShown) {
        Log.i(TAG, "🎵 Clearing musicPlayerWasShown (player only opens via sidebar now)")
        prefs.connection.musicPlayerWasShown = false
    }

    Log.i(TAG, "⚡ Music player manager initialized in ${System.currentTimeMillis() - startTime}ms")
}

internal fun HaliteComponentRegistry.initializeVideoFeed() {
    val startTime = System.currentTimeMillis()

    val overlayContainer = activityRef.findViewById<android.widget.FrameLayout>(com.dashieapp.Dashie.R.id.overlayContainer)
    if (overlayContainer == null) {
        Log.e(TAG, "Could not find overlayContainer for video feed!")
        return
    }

    // Refresh-aware token provider — sees expired access tokens and triggers
    // an async refresh via the stored refresh token, so video feed probes
    // (and any future periodic HA API caller) self-heal after the device
    // wakes up with an expired OAuth token. See HaTokenProvider.kt.
    val tokenProvider = com.dashieapp.Dashie.halite.HaTokenProvider(prefs)
    videoFeedManager = com.dashieapp.Dashie.halite.videofeed.VideoFeedOverlayManager(
        context = activityRef,
        overlayContainer = overlayContainer,
        haBaseUrlProvider = { prefs.connection.haBaseUrl },
        haTokenProvider = tokenProvider::get,
        alertSoundService = alertSoundService!!,
        visibilityGate = nativeWidgetVisibilityGate
    )

    // Apply settings from saved config
    videoFeedManager?.cooldownSeconds = prefs.videoFeed.getCooldownSeconds()
    videoFeedManager?.streamResolution = prefs.videoFeed.getStreamResolution()
    videoFeedManager?.streamFps = prefs.videoFeed.getStreamFps()
    videoFeedManager?.feedLocation = prefs.videoFeed.getFeedLocation()
    videoFeedManager?.feedLayout = prefs.videoFeed.getFeedLayout()
    videoFeedManager?.feedSize = prefs.videoFeed.getFeedSize()
    videoFeedManager?.prefsProvider = { prefs.videoFeed }
    // Restore the pop-ups-paused toggle from persistent storage (survives
    // app restart). prefsProvider must be wired first so the setter's
    // write-back goes to the same pref we just read.
    videoFeedManager?.isPaused = prefs.videoFeed.getPopupsPaused()
    videoFeedManager?.isAlertsMuted = prefs.videoFeed.getAlertsMuted()

    Log.i(TAG, "Video feed manager initialized in ${System.currentTimeMillis() - startTime}ms (cooldown=${videoFeedManager?.cooldownSeconds}s, res=${videoFeedManager?.streamResolution}, fps=${videoFeedManager?.streamFps}, location=${videoFeedManager?.feedLocation}, layout=${videoFeedManager?.feedLayout}, size=${videoFeedManager?.feedSize})")

    // Pull feeds from HA registry on startup so the camera popout has feed data
    prefs.videoFeed.pullFeedsFromHa()
}

internal fun HaliteComponentRegistry.initializePhotoWidget() {
    val startTime = System.currentTimeMillis()

    val widgetContainer = activityRef.findViewById<android.widget.FrameLayout>(com.dashieapp.Dashie.R.id.widgetContainer)
    if (widgetContainer == null) {
        Log.e(TAG, "Could not find widgetContainer for photo widget!")
        return
    }

    val screensaverPrefs = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences(activityRef)

    photoWidgetController = com.dashieapp.Dashie.halite.widgets.PhotoWidgetController(
        context = activityRef,
        rootContainer = widgetContainer,
        screensaverPrefs = screensaverPrefs,
        halitePrefs = prefs,
        webViewProvider = webViewProvider,
        sharedPhotosProvider = { screenController?.screenDimmer?.getSharedPhotos() ?: emptyList() },
        visibilityGate = nativeWidgetVisibilityGate
    )

    Log.i(TAG, "Photo widget controller initialized in ${System.currentTimeMillis() - startTime}ms")
}

internal fun HaliteComponentRegistry.initializeWeatherWidget() {
    val startTime = System.currentTimeMillis()

    val widgetContainer = activityRef.findViewById<android.widget.FrameLayout>(com.dashieapp.Dashie.R.id.widgetContainer)
    if (widgetContainer == null) {
        Log.e(TAG, "Could not find widgetContainer for weather widget!")
        return
    }

    val screensaverPrefs = com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences(activityRef)

    weatherDailyWidgetController = com.dashieapp.Dashie.halite.widgets.WeatherWidgetController(
        context = activityRef,
        rootContainer = widgetContainer,
        screensaverPrefs = screensaverPrefs,
        halitePrefs = prefs,
        webViewProvider = webViewProvider,
        visibilityGate = nativeWidgetVisibilityGate,
        mode = "daily"
    )

    weatherHourlyWidgetController = com.dashieapp.Dashie.halite.widgets.WeatherWidgetController(
        context = activityRef,
        rootContainer = widgetContainer,
        screensaverPrefs = screensaverPrefs,
        halitePrefs = prefs,
        webViewProvider = webViewProvider,
        visibilityGate = nativeWidgetVisibilityGate,
        mode = "hourly"
    )

    Log.i(TAG, "Weather widget controllers (daily+hourly) initialized in ${System.currentTimeMillis() - startTime}ms")
}

// ============================================================
// Music Player Methods
// ============================================================

/** Show music player with mock data for testing */
fun HaliteComponentRegistry.showMockMusicPlayer() {
    val mockData = com.dashieapp.Dashie.halite.music.MusicPlayerData(
        trackName = "Test Track Name",
        artistName = "Test Artist",
        albumName = "Test Album",
        albumArtUrl = null,
        isPlaying = true,
        positionSeconds = 45,
        durationSeconds = 253
    )
    musicPlayerManager?.showPlayer(mockData)
    Log.i(TAG, "🎵 Showing mock music player")
}

/** Hide music player (user-initiated from sidebar toggle) */
fun HaliteComponentRegistry.hideMusicPlayer() {
    musicPlayerManager?.userHidePlayer()
    Log.i(TAG, "🎵 Hiding music player (user-initiated)")
}

/** Check if music player is visible */
fun HaliteComponentRegistry.isMusicPlayerVisible(): Boolean = musicPlayerManager?.isPlayerVisible() ?: false

/** Load family members from JS bridge into the music profile manager */
internal fun HaliteComponentRegistry.loadFamilyMembersForMusicProfile() {
    // Hook into the existing family members loaded callback
    val delegate = jsBridge?.settingsDataDelegate
    val existingCallback = delegate?.onFamilyMembersLoaded
    delegate?.onFamilyMembersLoaded = { members ->
        // Forward to existing callback (settings page) if any
        existingCallback?.invoke(members)
        // Also feed into music profile manager
        musicPlayerManager?.musicProfileManager?.setFamilyMembers(members)
        Log.i(TAG, "🎵 Music profile manager: ${members.size} family members")
    }

    // Trigger the load
    webViewRef.post {
        webViewRef.evaluateJavascript("""
            (function() {
                try {
                    var svc = window.familyService;
                    if (!svc) return '';
                    svc.listMembers().then(function(members) {
                        window.DashieNative.onFamilyMembersLoaded(JSON.stringify(members));
                    });
                } catch(e) { }
                return '';
            })()
        """.trimIndent(), null)
    }
}

/**
 * Show music player by re-injecting the subscription.
 * This will check the current HA media_player state and show the player if media is playing.
 */
fun HaliteComponentRegistry.showMusicPlayer() {
    // If Sendspin is connected, show the player directly — no MA token or entity needed
    val sendspinActive = com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.activeClient != null
    if (sendspinActive && prefs.connection.musicPlayerEnabled) {
        Log.i(TAG, "🎵 Showing music player via Sendspin")
        musicPlayerManager?.clearDismissed()
        musicPlayerManager?.showWithLastKnownState()
        return
    }

    // Validate existing MA token on startup — after HA restore, locally-stored tokens
    // may be stale while the central store has a valid one from another device.
    if (prefs.connection.hasMaApiToken && prefs.connection.musicPlayerEnabled) {
        Thread {
            val apiUrl = prefs.connection.getEffectiveMaApiUrl()
            if (apiUrl.isNotEmpty()) {
                val client = com.dashieapp.Dashie.halite.music.MaApiClient(
                    baseUrl = apiUrl, token = prefs.connection.maApiToken
                )
                val user = client.getCurrentUser()
                if (user == null && client.authFailed) {
                    Log.w(TAG, "🎵 Startup: MA token is stale (401) — checking central store")
                    val centralToken = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring
                        .fetchCentralMaToken(prefs)
                    if (centralToken != null && centralToken.first != prefs.connection.maApiToken) {
                        prefs.connection.maApiToken = centralToken.first
                        prefs.connection.maApiUrl = centralToken.second
                        Log.i(TAG, "🎵 Startup: refreshed MA token from central store")
                    } else {
                        Log.w(TAG, "🎵 Startup: MA token expired, no fresh central token available")
                    }
                }
            }
        }.start()
    }

    // If no MA API token, try central store first, then prompt login
    if (!prefs.connection.hasMaApiToken && prefs.connection.musicPlayerEnabled) {
        val maUrl = prefs.connection.getEffectiveMaApiUrl()
        if (maUrl.isNotEmpty()) {
            Log.i(TAG, "🎵 No MA API token — checking central store first")
            Thread {
                val centralToken = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring
                    .fetchCentralMaToken(prefs)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (centralToken != null) {
                        prefs.connection.maApiToken = centralToken.first
                        prefs.connection.maApiUrl = centralToken.second
                        Log.i(TAG, "🎵 Got MA token from central store — showing player")
                        showMusicPlayer() // Retry now that we have the token
                    } else {
                        Log.i(TAG, "🎵 No central token — launching MA login")
                        val intent = android.content.Intent(activityRef, com.dashieapp.Dashie.halite.music.MaLoginActivity::class.java)
                        intent.putExtra("ma_url", maUrl)
                        activityRef.startActivity(intent)
                    }
                }
            }.start()
            return
        }
    }

    val entityId = prefs.connection.getEffectiveMusicPlayerEntityId()
    if (entityId.isNotEmpty() && prefs.connection.musicPlayerEnabled) {
        Log.i(TAG, "🎵 Showing music player - clearing stop flag and re-injecting for: $entityId")
        // Clear Kotlin-side dismissed guard so updateState() doesn't suppress the show
        musicPlayerManager?.clearDismissed()
        // Show immediately with last known data (paused view) as fallback.
        // If the HA entity has already cleared its state, the force re-inject
        // won't find anything to show - this ensures the player appears.
        musicPlayerManager?.showWithLastKnownState()
        // Defer JS injection to avoid blocking main thread during sidebar interaction.
        // The ~933-line JS injection is heavy and causes ANR if run synchronously
        // while the sidebar is still rendering.
        webViewRef.post {
            if (prefs.connection.hasAddonUrl) {
                // Shell/kiosk mode: HA is in an iframe, relay JS through shell page
                val escaped = "window._dashieMusicUserStopped = false;".replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")
                webViewRef.evaluateJavascript("evalInHaIframe(`$escaped`);", null)
                com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector.injectMusicPlayerSubscriptionViaShell(webViewRef, entityId, force = true)
            } else {
                webViewRef.evaluateJavascript("window._dashieMusicUserStopped = false;", null)
                com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector.injectMusicPlayerSubscription(webViewRef, entityId, force = true)
            }
        }
    } else if (entityId.isEmpty() && prefs.connection.musicPlayerEnabled) {
        // No default entity configured — auto-open picker for first-time setup
        Log.i(TAG, "🎵 No default entity configured — opening entity picker")
        val dialogs = com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs(activityRef, prefs)
        val recentEntities = prefs.connection.getRecentMusicPlayers()
        val useHa = prefs.connection.musicUseHaIntegration

        val onSelected = { selectedEntityId: String ->
            Log.i(TAG, "🎵 First-time setup: selected $selectedEntityId")
            prefs.connection.setDefaultMusicPlayer(selectedEntityId)
            prefs.connection.addRecentMusicPlayer(selectedEntityId)
            jsBridge?.sendMusicSwitchEntity(selectedEntityId)
            showMusicPlayer()
        }
        val onSetDefault = { entityId2: String ->
            prefs.connection.setDefaultMusicPlayer(entityId2)
        }

        // Preferred: MA REST API for player list (clean names, no _2 entities)
        val apiUrl2 = prefs.connection.getEffectiveMaApiUrl()
        val apiToken2 = prefs.connection.maApiToken
        val haBase2 = (prefs.connection.haBaseUrl.takeIf { it.isNotEmpty() } ?: prefs.connection.haUrl).trimEnd('/')
        val haToken2 = prefs.connection.haAccessToken
        if (apiUrl2.isNotEmpty() && apiToken2.isNotEmpty()) {
            Log.i(TAG, "🎵 First-time setup: using MA API for player list")
            Thread {
                try {
                    val apiClient = com.dashieapp.Dashie.halite.music.MaApiClient(
                        baseUrl = apiUrl2,
                        token = apiToken2
                    )
                    val arr = apiClient.getPlayers()
                    if (arr != null) {
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
                        Log.i(TAG, "🎵 First-time setup: ${players.size} MA API players available")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            dialogs.showMediaPlayerPickerDialogDirect("", players, recentEntities, onSelected, onSetDefault)
                        }
                    } else {
                        Log.e(TAG, "🎵 First-time setup: MA API returned null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 First-time setup: MA API failed: ${e.message}")
                }
            }.start()
        } else if (!useHa) {
            // Legacy direct-MA mode: fetch from MA streams proxy
            val maServer = dashieServiceManager?.audioManager?.maServerUrl?.takeIf { it.isNotEmpty() }
                ?: prefs.connection.maServerUrl.takeIf { it.isNotEmpty() }
                ?: try {
                    val haBaseUrl = prefs.connection.haBaseUrl.takeIf { it.isNotEmpty() } ?: prefs.connection.haUrl
                    val haUrl = java.net.URL(haBaseUrl)
                    "${haUrl.protocol}://${haUrl.host}:8097"
                } catch (_: Exception) { null }

            if (maServer != null) {
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
                        Log.i(TAG, "🎵 First-time setup: ${players.size} MA players available")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            dialogs.showMediaPlayerPickerDialogDirect("", players, recentEntities, onSelected, onSetDefault)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "🎵 First-time setup: MA server unavailable: ${e.message}")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(activityRef, "Music server unavailable — try again in a moment", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                android.widget.Toast.makeText(activityRef, "Music server not connected yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else if (prefs.connection.hasAddonUrl) {
            com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector.queryMediaPlayersViaShell(webViewRef) { jsonString ->
                activityRef.runOnUiThread {
                    dialogs.showEntityPickerWithPlayers(jsonString, "", recentEntities, onSelected, onSetDefault)
                }
            }
        } else {
            dialogs.webView = webViewRef
            dialogs.showEntityPicker("", recentEntities, onSelected, onSetDefault)
        }
    } else {
        Log.w(TAG, "🎵 Cannot show music player - not configured or disabled")
    }
}

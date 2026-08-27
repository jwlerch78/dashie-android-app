package com.dashieapp.Dashie.halite.wiring

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.registry.*

import com.dashieapp.Dashie.halite.music.MaVoicePlay
import com.dashieapp.Dashie.halite.music.SpeakerNameMatcher

/**
 * Voice controller callback wiring extracted from HaliteComponentWiring.
 *
 * Handles RTSP coordination, local command interception (timer, music, volume),
 * video feed voice commands, and speaker targeting.
 */
object VoiceComponentWiring {
    private const val TAG = "VoiceWiring"

    /**
     * Wire voice controller callbacks for RTSP coordination and local command interception.
     */
    fun wireVoiceCallbacks(registry: HaliteComponentRegistry, webViewProvider: () -> WebView) {
        val voice = registry.voiceController ?: return
        val dashieService = registry.dashieServiceManager ?: return

        // Two-phase audio handling for voice interactions:
        // 1. Duck on wake word: lower MA volume so STT can hear over music
        // 2. Pause for TTS: when AI response is coming with voice, pause MA
        //    so the TTS plays at full speaker volume (ducking would mute TTS too)
        //
        // For local commands (silent), we just unduck after — never pause.
        var preDuckMaVolume: Int? = null
        var wasPausedForTts = false

        voice.onDuckMusic = {
            dashieService.duckAudio()  // ExoPlayer (RTSP) ducks volume
            // LOCAL Sendspin playback ducks via a software gain FIRST — the MA volume
            // command below round-trips back to this device as setStreamVolume(MUSIC, ~0),
            // which silences local TTS AND Gemini Live playback (they share the stream).
            // With the voice duck armed, the service suppresses that stream write and the
            // gain quiets only the music (field report 2026-07-12).
            com.dashieapp.Dashie.halite.music.sendspin.VoiceDuckController.set(true)
            // Duck MA player so STT can hear over the music
            Thread {
                try {
                    val entity = MusicComponentWiring.resolveEntityForCommand(registry)
                        ?: registry.prefs.connection.recentMusicPlayerEntityIds
                            .takeIf { it.isNotEmpty() }
                            ?.split(",")?.firstOrNull()?.trim()
                        ?: return@Thread
                    val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread
                    val currentData = registry.musicPlayerManager?.getCurrentData()
                    // Only duck a player that is actually playing. Ducking a paused
                    // player needlessly lowers its volume, and when that player is this
                    // device (Sendspin → STREAM_MUSIC), it also mutes local TTS, which
                    // rides the same stream (STREAM_TTS is aliased to STREAM_MUSIC on
                    // Samsung). The paused volume is never restored until after TTS ends.
                    val currentPct = currentData?.let { (it.volumeLevel * 100).toInt() }
                    if (currentData?.isPlaying == true && currentPct != null && currentPct > 5) {
                        preDuckMaVolume = currentPct
                        apiClient.setVolume(entity, 5)
                        Log.i(TAG, "🎵 MA ducked: $currentPct → 5 on $entity")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 MA duck failed: ${e.message}")
                }
            }.start()
        }

        voice.onUnduckMusic = {
            dashieService.unduckAudio()
            Thread {
                // Restore the MA volume FIRST (below), then lift the local suppression at the
                // end of this thread — so the restore command's stream write isn't suppressed.
                try {
                    val entity = MusicComponentWiring.resolveEntityForCommand(registry)
                        ?: registry.prefs.connection.recentMusicPlayerEntityIds
                            .takeIf { it.isNotEmpty() }
                            ?.split(",")?.firstOrNull()?.trim()
                        ?: return@Thread
                    val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread

                    // If we paused for TTS, resume playback (which restores volume implicitly)
                    if (wasPausedForTts) {
                        wasPausedForTts = false
                        apiClient.sendPlayerCommand(entity, "play")
                        Log.i(TAG, "🎵 MA resumed after TTS on $entity")
                        // After resuming, restore volume if we had a pre-duck value
                        val restoreVol = preDuckMaVolume
                        if (restoreVol != null) {
                            preDuckMaVolume = null
                            Thread.sleep(200)  // Brief delay to let resume take effect
                            apiClient.setVolume(entity, restoreVol)
                            Log.i(TAG, "🎵 MA volume restored: → $restoreVol on $entity")
                        }
                    } else {
                        // Just restore volume from duck
                        val restoreVol = preDuckMaVolume
                        if (restoreVol != null) {
                            preDuckMaVolume = null
                            apiClient.setVolume(entity, restoreVol)
                            Log.i(TAG, "🎵 MA unducked: → $restoreVol on $entity")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 MA unduck failed: ${e.message}")
                } finally {
                    // Lift the local software duck + stream-write suppression. Runs after the
                    // MA restore above so the server's echo of the restored volume (arriving
                    // later over the socket) is applied normally; setVoiceDuck(false) itself
                    // re-syncs the stream from the last recorded server volume.
                    com.dashieapp.Dashie.halite.music.sendspin.VoiceDuckController.set(false)
                }
            }.start()
        }

        // New: pause MA when TTS is about to play (called when AI response arrives
        // with voice text, just before TTS playback starts)
        voice.onPauseForTts = {
            Thread {
                try {
                    val entity = MusicComponentWiring.resolveEntityForCommand(registry)
                        ?: registry.prefs.connection.recentMusicPlayerEntityIds
                            .takeIf { it.isNotEmpty() }
                            ?.split(",")?.firstOrNull()?.trim()
                        ?: return@Thread
                    val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread
                    val currentData = registry.musicPlayerManager?.getCurrentData()
                    if (currentData?.isPlaying == true) {
                        // Pause FIRST (while still ducked at ~5%) so restoring the
                        // volume doesn't briefly blast the music before the pause
                        // lands — that was the audible "restart" on the way to
                        // thinking. Then restore the volume while it's paused
                        // (silent), so both the resume after TTS and the local TTS
                        // itself (which rides STREAM_MUSIC) play at full level.
                        wasPausedForTts = true
                        apiClient.sendPlayerCommand(entity, "pause")
                        Log.i(TAG, "🎵 MA paused for TTS on $entity")
                        val restoreVol = preDuckMaVolume
                        if (restoreVol != null) {
                            Thread.sleep(150)  // let the pause land before bumping volume
                            apiClient.setVolume(entity, restoreVol)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 MA pause for TTS failed: ${e.message}")
                }
            }.start()
        }

        // Stop in-flight TTS when a new wake word lands. In cloud mode the response
        // is spoken by the JS-bridge TTS player (driven by JS speak() calls), which
        // the voice pipeline can't reach — without this, interrupting mid-response
        // leaves it playing and the audio bleeds into the new STT. stopSpeaking()
        // posts to the WebView thread and covers both the VA and the bridge player.
        voice.onStopTts = {
            registry.jsBridge?.stopSpeaking()
        }

        // Wake the screen on a wake word. Routes to the shared wake logic (WAKE PATH 5);
        // voice always wakes (no sleep gate / power-button grace — it's a deliberate action).
        voice.onWakeScreen = {
            registry.screenController?.onVoiceWake()
        }

        // Confirmation-tone mode: play the configured chime (at its own volume)
        // instead of speaking. Reuses AlertSoundService's preloaded SoundPool.
        voice.onPlayConfirmationTone = {
            val tone = registry.prefs.voice.confirmationToneType
            val vol = registry.prefs.voice.confirmationToneVolume
            registry.alertSoundService?.playConfirmationTone(tone, vol)
                ?: Log.w(TAG, "🔊 Confirmation tone requested but AlertSoundService unavailable")
        }

        // BEFORE voice initializes, stop RTSP to release audio HAL
        voice.onVoiceWillInitialize = {
            if (dashieService.isRtspServerRunning()) {
                Log.i(TAG, "🎤 Voice will initialize - stopping RTSP to release audio HAL")
                dashieService.stopRtspServer()
            } else {
                Log.i(TAG, "🎤 Voice will initialize - RTSP not running, no need to stop")
            }
        }

        // When voice initializes, pass SharedAudioBuffer to RTSP server
        voice.onVoiceInitialized = {
            val sharedBuffer = voice.getSharedAudioBuffer()
            Log.i(TAG, "🎤 Voice initialized - SharedAudioBuffer: ${if (sharedBuffer != null) "available" else "null"}")
            if (sharedBuffer != null) {
                dashieService.setSharedAudioBuffer(sharedBuffer)
                Log.i(TAG, "✓ SharedAudioBuffer passed to RTSP server for audio sharing")

                // Start RTSP with shared audio if it was enabled (deferred from startRtspIfEnabled)
                if (registry.prefs.camera.rtspEnabled && !dashieService.isRtspServerRunning()) {
                    Log.i(TAG, "🎤 Starting RTSP with shared audio after voice init (1s delay)")
                    registry.screenController?.enableRtspMotionMode()
                    Handler(Looper.getMainLooper()).postDelayed({
                        dashieService.startRtspServer()
                        // Wire face frame callback so RTSP frames reach the face detector
                        registry.wireRtspFaceFrameCallback()
                        // Re-apply HA sensor config so motion/face callbacks are wired to the new RTSP instance
                        SensorComponentWiring.applyHaSensorConfig(registry)
                    }, 1000)
                }
            }
        }

        // --- Local command interception (timer, music, volume) ---

        // Music playing state for flexible media matching
        voice.isMusicPlayingProvider = { dashieService.isMusicPlaying() }

        // Alarm playing state (any timer in COMPLETED state = alarm ringing)
        voice.isAlarmPlayingProvider = {
            registry.timerOverlayManager?.hasAlarmPlaying() ?: false
        }

        // Silence a ringing alarm on the wake word itself — HaliteVoiceController
        // invokes this when a wake word fires while an alarm is ringing, so the user
        // doesn't have to fight the alarm to issue a command.
        voice.onStopAlarm = { registry.timerOverlayManager?.stopAlarm() }

        // Timer remaining for voice queries ("how much time is left?")
        voice.timerRemainingProvider = {
            registry.timerOverlayManager?.getFirstActiveTimerRemaining()
        }

        // Volume controls via device audio manager (through JS bridge's device controls)
        voice.onVolumeUp = { amount -> registry.jsBridge?.volumeUp(amount) }
        voice.onVolumeDown = { amount -> registry.jsBridge?.volumeDown(amount) }
        voice.onSetVolume = { level -> registry.jsBridge?.setVolume(level) }

        // Timer commands - send to overlay iframe via postMessage
        voice.onTimerCommand = { command, params ->
            if (command == "stop_alarm") {
                registry.timerOverlayManager?.stopAlarm()
            }
            sendTimerCommandToOverlay(webViewProvider, command, params)
            // Local intercept fires AFTER HA already received the
            // transcript and started its own pipeline (3ms gap between
            // our disconnect and HA's intent-start in observed logs).
            // Result: HA also creates a timer that fires its own alarm
            // in parallel with ours. Send a follow-up "cancel timer"
            // conversation request so HA cancels the duplicate.
            // Only needed for create commands — query/cancel/stop_alarm
            // don't trigger HA timer creation.
            if (command == "start_timer" || command == "create") {
                cancelHaShadowTimer(webViewProvider)
            }
        }

        // Scheduled actions / reminders — create an on-device reminder (Phase 1).
        // The classifier's ttsResponse is spoken by the voice pipeline; the manager
        // only persists + arms the AlarmManager alarm.
        voice.onScheduleCommand = { command, params ->
            if (command == "create_reminder") {
                registry.scheduledActionManager?.createReminder(
                    notifyText = params["reminderText"] as? String ?: "",
                    delaySeconds = (params["delaySeconds"] as? Int) ?: 0,
                    vernacular = params["vernacular"] as? String
                        ?: com.dashieapp.Dashie.halite.schedule.ScheduledAction.VERNACULAR_REMINDER
                )
            }
        }

        // Dashie Cloud voice path: the JS classifier intercepts "remind me …" and
        // calls DashieNative.scheduleReminder → forward to the same manager so both
        // voice paths share one device-owned reminder store.
        registry.jsBridge?.scheduleDelegate?.onScheduleReminder = { text, delay, vern ->
            registry.scheduledActionManager?.createReminder(
                notifyText = text,
                delaySeconds = delay,
                vernacular = vern
            )
        }

        // Voice-enrollment wizard ("learn my voice") — Dashie Cloud voice path
        // only for v1: JS classifier → DashieNative.startVoiceEnrollment with
        // the family-member list (JS owns familyService; Kotlin never fetches
        // members). No kiosk/HA-local trigger: offline mode has no family
        // members to enroll against.
        registry.jsBridge?.speakerIdDelegate?.let { sid ->
            sid.onStartEnrollment = { members ->
                registry.voiceEnrollmentController?.startEnrollment(members)
            }
            sid.speakerIdAvailabilityProvider = {
                registry.voiceEnrollmentController?.isAvailable == true
            }
        }

        // Cloud mirror (Phase 2): console edits/deletes ride Realtime → JS → these
        // bridge callbacks → the same manager, which re-arms/cancels the alarm.
        registry.jsBridge?.scheduleDelegate?.let { sd ->
            sd.onCancelReminder = { id -> registry.scheduledActionManager?.cancel(id) }
            sd.onUpdateReminder = { id, text, fireAt, vern ->
                registry.scheduledActionManager?.update(id, text, fireAt, vern)
            }
            // Condition alerts ("tell me when the hot tub is at 102") — JS
            // classifier → this bridge → resolve entity + arm adaptive poller.
            sd.onCreateConditionAlert = { phrase, op, value ->
                registry.conditionAlertManager?.create(phrase, op, value)
            }
        }
        // Lifecycle: manager create/fire/cancel/update → the cloud mirror. On a dashboard
        // device the JS path owns it (reminder-sync.js also broadcasts to the console); on a
        // kiosk the full webapp never loads, so the NATIVE mirror owns it (ScheduleCloudMirror
        // — before this, the emit retried 6× and dropped, and the console never saw kiosk
        // reminders). The onDropped fallback additionally heals a dashboard device whose JS
        // handler never registered (app restarting when an alarm fired).
        registry.scheduledActionManager?.onLifecycle = { event, action ->
            if (com.dashieapp.Dashie.halite.schedule.ScheduleCloudMirror.ownsMirror()) {
                com.dashieapp.Dashie.halite.schedule.ScheduleCloudMirror.mirror(event, action)
            } else {
                registry.jsBridge?.scheduleDelegate?.emitReminderEvent(
                    event, action.toJson().toString(),
                    onDropped = { com.dashieapp.Dashie.halite.schedule.ScheduleCloudMirror.mirror(event, action) })
            }
        }

        // AI callbacks (WS5-a): TYPE_AI_TURN actions inject their stored prompt
        // into the voice pipeline at fire time — same lane as @pipeline injection.
        // Static (survives manager recreation): returns true only when the voice
        // pipeline is available, so a fire mid-restart retries instead of failing.
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.injectAiTurn = { prompt ->
            val vc = registry.voiceController
            // Park the announcement behind any live conversation instead of barging in.
            // Firing blind truncated Dashie mid-answer (a nightly joke cut off "The capital
            // of France is Paris." 2.8s in) or seized the mic armed for the user's follow-up.
            //
            // runWhenIdle returns false ONLY if the pipeline isn't wired yet → the manager's
            // retry loop handles that (a fire mid-restart). A QUEUED turn returns true, so the
            // manager stops retrying and doesn't burn its ~48s budget — otherwise a chat that
            // outlasts the budget would degrade a perfectly good reminder into a "couldn't run"
            // card. "Busy" is not "broken".
            vc?.runWhenIdle("scheduled-announcement") {
                // FRAME the replay as a fired callback, not a fresh request. The stored prompt
                // is often the user's own words ("remind me to check the oven"); replayed
                // verbatim, the model read it as a NEW reminder request and asked "how long
                // from now?" AT FIRE TIME (Samsung kiosk, 2026-07-18 04:17). The framing makes
                // it act/announce; the anti-recursion guard still blocks any re-schedule.
                val framed = "A scheduled reminder just fired: \"$prompt\". Carry that out now — " +
                    "announce it or do the check it describes, in one short line. Never create " +
                    "a new reminder and never ask when to schedule it."
                // Arm the anti-recursion guard at ACTUAL inject time, not queue time — its
                // window is wall-clock, so arming it before a wait would let it expire.
                // (Guarding the FRAMED text still matches: isSelfReschedule tests containment,
                // and the raw prompt is inside the framing.)
                com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.beginInjectedTurn(framed)
                // announcement=true: fire as a one-way single-shot, no mic re-arm / dialog loop.
                vc.injectTranscript(framed, announcement = true)
            } ?: false
        }
        // Presentation gate: a scheduled action's card waits for the user to finish talking to
        // Dashie rather than dropping a modal over a live chat. Presentation only — the HA
        // command itself already ran on time.
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.deferUntilIdle = { tag, block ->
            registry.voiceController?.runWhenIdle(tag, block) ?: false
        }
        // Creation-time confirmation card ("Reminder · Today at 5:12 PM") — parity with full
        // mode's creation text line. Before this, a brain-scheduled action (kiosk, or full-mode
        // brain turns) was voice-only until it fired.
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showCreatedCard = { msg, detail ->
            registry.voiceController?.showScheduleConfirmation(msg, detail)
        }
        // TYPE_HA_COMMAND fire (kind='command'): prefer the full webapp's JS global (it builds
        // the rich confirmation), but that global is registered by reminder-sync.js, which the
        // KIOSK bundle does not load — so PROBE it and fall back to running the command NATIVELY
        // via HA Assist. The old blind evaluateJavascript threw "dashieRunScheduledHaCommand is
        // not a function" on the kiosk while this lambda reported success → the scheduled command
        // silently never ran (Samsung kiosk, 2026-07-17 23:41).
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.runHaCommand = { prompt ->
            val wv = webViewProvider()
            if (wv != null) {
                // BUNDLE-EXEMPT: dashieRunScheduledHaCommand — kiosk falls back to runScheduledHaCommandNatively when the JS probe fails (2026-07 fire-time fix)
                val js = """(function(){
                    if (typeof window.dashieRunScheduledHaCommand === 'function') {
                        window.dashieRunScheduledHaCommand(${org.json.JSONObject.quote(prompt)});
                        return true;
                    }
                    return false;
                })();"""
                wv.post {
                    wv.evaluateJavascript(js) { result ->
                        if (result != "true") {
                            Log.i(TAG, "⏰ dashieRunScheduledHaCommand unavailable — running scheduled HA command natively")
                            runScheduledHaCommandNatively(registry, prompt)
                        }
                    }
                }
                true
            } else false
        }
        // Creation: brain returns client_tool schedule_action → coordinator →
        // this statically-wired handler (keeps the size-gated coordinator thin).
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.manager =
            registry.scheduledActionManager

        // Show music player when voice play command is issued
        voice.onMusicPlayInitiated = {
            registry.musicPlayerManager?.clearDismissed()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                registry.musicPlayerManager?.showWithLastKnownState()
            }
        }

        // Voice music commands → MA API (play_media, play, pause, next, etc.)
        // This is the Kotlin-side interceptor path (HA Assist pipeline → KotlinIntentPatterns)
        // play_media uses the default speaker from settings (user's configured default)
        // Playback controls use the active entity (what's currently playing)
        voice.onMusicCommand = { command, paramsJson ->
            Log.i(TAG, "🎵 Voice music command (Kotlin intercept) via MA API: $command")
            Thread {
                try {
                    // Resolve entity: use current player, but for play_media fall back to
                    // settings default if current is a Sendspin ID (up*) that can't queue media
                    val currentEntity = MusicComponentWiring.musicCoordinator?.getCommandEntity()
                        ?: MusicComponentWiring.resolveEntityForCommand(registry)
                    // For play_media with search strings (no URI), Sendspin players can't
                    // resolve the search — need a real media_player.* entity.
                    // Specific URIs (library://, spotify://) work fine on Sendspin players.
                    val needsSearchResolve = command == "play_media" &&
                        currentEntity?.startsWith("up") == true &&
                        paramsJson.let {
                            val mediaId = try { org.json.JSONObject(it).optString("mediaId", "") } catch (_: Exception) { "" }
                            !mediaId.contains("://")
                        }
                    val entity = if (needsSearchResolve) {
                        val apiClient = MusicComponentWiring.createMaApiClient(registry)
                        val resolved = apiClient?.resolvePlayableEntity(currentEntity!!)
                        resolved?.takeIf { !it.startsWith("up") }
                            ?: registry.prefs.connection.getEffectiveMusicPlayerEntityId().takeIf { it.isNotEmpty() && !it.startsWith("up") }
                    } else {
                        currentEntity
                    }
                    if (entity == null) {
                        Log.w(TAG, "🎵 No music entity for voice command '$command'")
                        return@Thread
                    }
                    Log.d(TAG, "🎵 Voice command '$command' → entity=$entity")
                    when (command) {
                        "play_media" -> {
                            val profileClient = MusicComponentWiring.createProfileScopedMaApiClient(registry)
                                ?: MusicComponentWiring.createMaApiClient(registry)
                            if (profileClient != null) {
                                MaVoicePlay.playFromVoiceParams(
                                    profileClient, entity, org.json.JSONObject(paramsJson)
                                )
                            }
                        }
                        "play", "pause", "next", "previous", "stop", "play_pause" ->
                            MusicComponentWiring.sendMaCommand(registry, entity, command)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Voice music command failed: ${e.message}", e)
                }
            }.start()
        }

        // Open external app by voice ("open Netflix / YouTube TV / Spotify …")
        val appLaunchController = com.dashieapp.Dashie.halite.apps.AppLaunchController(registry.activityRef)
        // Share the Activity-scoped controller with the realtime tool dispatcher (open_app tool)
        // and the cloud pre-intercept, both of which reach it via this process-lifetime handle.
        com.dashieapp.Dashie.halite.apps.AppLaunchController.instance = appLaunchController
        voice.onOpenAppCommand = { _, params ->
            val query = params["query"] as? String ?: ""
            val knownPackage = params["knownPackage"] as? String ?: ""
            val knownLabel = params["knownLabel"] as? String ?: ""
            Handler(Looper.getMainLooper()).post {
                appLaunchController.openApp(query, knownPackage, knownLabel)
            }
        }

        // Video feed voice commands (show/hide cameras by name) — executed by the ONE native
        // tool shared with the brain + Live lanes (native-executor convergence 2026-07-19).
        // The local lane ignores the returned voice line (the feed appearing IS the
        // confirmation); its one behavioral nuance — dismiss-with-no-match closes whatever's
        // open — is preserved below rather than duplicated inside the tool.
        val videoFeedTool = com.dashieapp.Dashie.halite.videofeed.VideoFeedVoiceTool(
            managerProvider = { registry.videoFeedManager },
            rulesProvider = {
                registry.videoFeedManager?.prefsProvider?.invoke()?.getEnabledRules() ?: emptyList()
            },
        )
        com.dashieapp.Dashie.halite.videofeed.VideoFeedVoiceTool.instance = videoFeedTool
        voice.onVideoFeedCommand = { command, params ->
            val action = when (command) {
                "show" -> "show"; "dismiss" -> "hide"
                "show_all" -> "show_all"; "dismiss_all" -> "hide_all"
                else -> null
            }
            if (action == null) {
                Log.w(TAG, "📹 DROP: unknown local video feed command '$command'")
            } else {
                val feedName = params["feedName"] as? String
                val out = videoFeedTool.execute(
                    org.json.JSONObject().put("action", action).apply {
                        if (feedName != null) put("camera", feedName)
                    })
                Log.i(TAG, "📹 Voice($command): ${out.optString("voice")}")
                // Local nuance: a named dismiss that matched nothing still closes the
                // drawer/strip if open (the user is asking cameras to go away).
                if (action == "hide" && !out.optBoolean("found") && feedName != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        registry.videoFeedManager?.dismissFeedByVoice("")
                    }
                }
            }
        }

        // Shuffle/repeat voice commands → MA API
        voice.onPlaybackModeCommand = { command ->
            Thread {
                try {
                    val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread
                    val entity = MusicComponentWiring.resolveEntityForCommand(registry) ?: return@Thread
                    when (command) {
                        "shuffle_on" -> apiClient.setShuffle(entity, true)
                        "shuffle_off" -> apiClient.setShuffle(entity, false)
                        "repeat_one" -> apiClient.setRepeat(entity, "one")
                        "repeat_all" -> apiClient.setRepeat(entity, "all")
                        "repeat_off" -> apiClient.setRepeat(entity, "off")
                    }
                    Log.i(TAG, "🎵 Playback mode $command for $entity → OK")
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Playback mode $command failed: ${e.message}")
                }
            }.start()
        }

        // Mute/unmute speaker by spoken name → resolve + MA API
        val speakerMatcher = SpeakerNameMatcher {
            MusicComponentWiring.createMaApiClient(registry)?.getPlayers()
        }
        voice.onSpeakerMuteCommand = { speakerName, muted ->
            Thread {
                try {
                    val match = speakerMatcher.resolveWithName(speakerName)
                    if (match != null) {
                        val (playerId, displayName) = match
                        val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread
                        apiClient.mutePlayer(playerId, muted)
                        val action = if (muted) "Muted" else "Unmuted"
                        Log.i(TAG, "🎵 $action $displayName ($playerId)")
                    } else {
                        Log.w(TAG, "🎵 No speaker match for '$speakerName'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Speaker mute failed: ${e.message}")
                }
            }.start()
        }

        // Play on specific speaker by spoken name → resolve + transfer + play
        voice.onPlayOnSpeaker = { query, speakerName, params ->
            Thread {
                try {
                    val match = speakerMatcher.resolveWithName(speakerName)
                    if (match != null) {
                        val (targetPlayerId, displayName) = match
                        val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread
                        // Transfer queue to target speaker, then play media
                        val currentEntity = MusicComponentWiring.resolveEntityForCommand(registry)
                        if (currentEntity != null && currentEntity != targetPlayerId) {
                            apiClient.transferQueue(currentEntity, targetPlayerId)
                            Log.i(TAG, "🎵 Transferred queue to $displayName ($targetPlayerId)")
                        }
                        // Update current entity to the target
                        registry.currentMusicEntityId = targetPlayerId
                        // Play the media on the target
                        apiClient.playMedia(targetPlayerId, query)
                        Log.i(TAG, "🎵 Playing '$query' on $displayName ($targetPlayerId)")
                    } else {
                        // Fallback: play on current speaker, ignore speaker targeting
                        Log.w(TAG, "🎵 No speaker match for '$speakerName', playing on current")
                        val entity = MusicComponentWiring.resolveEntityForCommand(registry)
                        if (entity != null) {
                            val apiClient = MusicComponentWiring.createMaApiClient(registry) ?: return@Thread
                            apiClient.playMedia(entity, query)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🎵 Play on speaker failed: ${e.message}")
                }
            }.start()
        }
    }

    // findFeedByName moved to VideoFeedVoiceTool (the ONE native camera executor) — 2026-07-19.

    /**
     * Send a timer command from voice interception to the overlay iframe via postMessage.
     * Maps KotlinIntentPatterns command names to overlay timer-command format.
     */
    private fun sendTimerCommandToOverlay(
        webViewProvider: () -> WebView,
        command: String,
        params: Map<String, Any>
    ) {
        val webView = webViewProvider()

        // Map KotlinIntentPatterns commands to overlay timer-command names
        val overlayCommand = when (command) {
            "start_timer" -> "create"
            "pause_timer" -> "pause"
            "resume_timer" -> "resume"
            "cancel_timer" -> "cancel"
            "stop_alarm" -> "cancel"  // Cancel the completed/alarming timer
            "add_time" -> "add_time"
            "subtract_time" -> "subtract_time"
            else -> return
        }

        // Build params JS for the postMessage
        val paramsJs = buildString {
            when (overlayCommand) {
                "create" -> {
                    val seconds = params["durationSeconds"] ?: return
                    val desc = params["description"]
                    append("durationSeconds: $seconds")
                    if (desc != null && desc.toString().isNotEmpty()) {
                        append(", description: '${desc.toString().replace("'", "\\'")}'")
                    }
                }
                "add_time" -> {
                    val seconds = params["addSeconds"] ?: return
                    append("addSeconds: $seconds")
                }
                "subtract_time" -> {
                    val seconds = params["subtractSeconds"] ?: return
                    append("subtractSeconds: $seconds")
                }
            }

            // Append timer reference (name or slot) for targeted commands
            val timerSlot = params["timerSlot"]
            val timerName = params["timerName"]
            if (timerSlot != null) {
                if (isNotEmpty()) append(", ")
                append("timerSlot: $timerSlot")
            }
            if (timerName != null) {
                if (isNotEmpty()) append(", ")
                append("timerName: '${timerName.toString().replace("'", "\\'")}'")
            }
        }

        webView.post {
            webView.evaluateJavascript("""
                (function() {
                    var overlay = document.getElementById('dashie-overlay');
                    if (overlay && overlay.contentWindow) {
                        overlay.contentWindow.postMessage({
                            source: 'dashie-parent',
                            type: 'timer-command',
                            command: '$overlayCommand'${if (paramsJs.isNotEmpty()) ", $paramsJs" else ""}
                        }, '*');
                    }
                })();
            """.trimIndent(), null)
        }

        Log.i(TAG, "🎤 Timer command sent to overlay: $overlayCommand")
    }

    /**
     * Cancel the parallel timer HA created when its Assist pipeline
     * processed the transcript before our local intercept's WebSocket
     * disconnect propagated. Sent via JS conversation.process — HA's
     * conversation engine interprets "cancel timer" and stops the most
     * recently-created timer.
     *
     * Fire-and-forget: if HA didn't create a timer (intercept won the
     * race), the cancel is a no-op. If we miss (HA timer survives),
     * the user has to use voice to stop it — but at worst we revert to
     * pre-fix behavior.
     */
    private fun cancelHaShadowTimer(webViewProvider: () -> android.webkit.WebView) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val webView = webViewProvider()
            // Use the existing window.hass connection that the WebView
            // dashboard establishes. callWS('conversation/process', ...)
            // sends the cancel request without us needing our own HA
            // WebSocket. Wrapped in try/catch since hass may not be
            // available on every page (login screen etc).
            // EXTERNAL-GLOBAL: hass — HA frontend's own global, read inside the HA iframe
            val js = """
                (function() {
                    try {
                        const hass = window.hass?.hass || window.hass;
                        if (hass && typeof hass.callWS === 'function') {
                            hass.callWS({
                                type: 'conversation/process',
                                text: 'cancel timer',
                                language: 'en'
                            }).then(() => console.log('[DashieTimer] HA shadow timer cancel sent'))
                              .catch(e => console.warn('[DashieTimer] HA shadow timer cancel failed', e));
                        } else {
                            console.warn('[DashieTimer] hass not available — skipping HA timer cancel');
                        }
                    } catch (e) {
                        console.warn('[DashieTimer] HA timer cancel threw', e);
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            Log.i(TAG, "🎤 Sent HA shadow-timer cancel request")
        }
    }

    /**
     * Native fire-time executor for a scheduled HA command — the kiosk twin of reminder-sync.js
     * `runScheduledHaCommand` (that JS global only exists in the full webapp). Runs Assist off-main
     * and presents through the same scheduled-HA card/error surfaces the JS path calls back into.
     */
    private fun runScheduledHaCommandNatively(registry: HaliteComponentRegistry, prompt: String) {
        Thread({
            val assist = com.dashieapp.Dashie.voice.realtime.HaAssistConverse { registry.prefs }
            val result = runCatching { assist.process(prompt) }.getOrNull()
            val directive = com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective
            if (result?.actionDone == true) {
                Log.i(TAG, "⏰ Scheduled HA command ran natively: \"${prompt.take(60)}\"")
                directive.showScheduledHaCard(result.speech?.takeIf { it.isNotBlank() } ?: "Done: $prompt")
            } else {
                Log.w(TAG, "⏰ Scheduled HA command FAILED natively: \"${prompt.take(60)}\" (${result?.speech ?: "no HA response"})")
                directive.showScheduledHaError(result?.speech?.takeIf { it.isNotBlank() } ?: "Couldn't run \"$prompt\"")
            }
        }, "scheduled-ha-native").apply { isDaemon = true }.start()
    }
}

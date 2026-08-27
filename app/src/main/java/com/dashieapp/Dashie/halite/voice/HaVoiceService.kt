package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface
import org.json.JSONObject

/**
 * Home Assistant Voice Service for Dashie Lite
 *
 * Orchestrates voice control of Home Assistant through the Assist pipeline.
 *
 * Flow:
 * 1. Wake word detected ("Hey Dashie") via Edge Impulse
 * 2. Extract HA auth token from WebView localStorage
 * 3. Connect to HA WebSocket and start Assist pipeline
 * 4. Stream audio from SharedAudioBuffer to HA
 * 5. Detect end-of-speech and stop streaming
 * 6. Receive TTS response and play it
 * 7. Resume wake word detection
 *
 * This service integrates with the existing audio infrastructure:
 * - AudioCaptureService: Continuous mic recording to SharedAudioBuffer
 * - EdgeImpulseDetector: Wake word detection
 * - SharedAudioBuffer: Circular buffer shared between wake word and voice
 */
class HaVoiceService(
    private val context: Context,
    private val webViewProvider: () -> WebView,
    private val haUrl: String
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()
    companion object {
        private const val TAG = "HaVoiceService"

        // Audio chunk size for streaming (100ms at 16kHz, 16-bit mono)
        private const val AUDIO_CHUNK_SIZE = 3200  // 1600 samples * 2 bytes

        // Maximum recording duration (seconds)
        private const val MAX_RECORDING_DURATION_SEC = 10

        // How long to wait for the pipeline list before planning the turn without it. The request
        // rides the socket we just authenticated (~15ms on a LAN), and an unknown pipeline simply
        // means "don't block" — so this is a small, safe budget.
        private const val PIPELINE_LIST_TIMEOUT_MS = 1500L

        // Watchdog timeout for PROCESSING state (ms) - if no response in 10 seconds, timeout
        private const val PROCESSING_TIMEOUT_MS = 10000L
        // Time allowed for the connect → onAuthSuccess transition. Covers
        // TCP connect, TLS handshake, WebSocket upgrade, and HA Assist auth
        // handshake. If we're still in CONNECTING after this we give up
        // rather than leaving the UI stuck on "Connecting…" indefinitely.
        private const val CONNECT_TIMEOUT_MS = 15000L
        /** Longest a turn may sit in LISTENING with no transcript. Generous — it is a
         *  hang-breaker, not a speech-length limit; a real utterance finalises far sooner. */
        private const val LISTENING_TIMEOUT_MS = 20000L
    }

    // Voice state
    enum class VoiceState {
        IDLE,           // Waiting for wake word
        CONNECTING,     // Connecting to HA WebSocket
        LISTENING,      // Recording user speech
        PROCESSING,     // HA processing command
        SPEAKING,       // Playing TTS response
        ERROR           // Error state
    }

    // Components
    private var assistClient: HaAssistClient? = null
    private var ttsPlayer: HaTtsPlayer? = null
    private var vad: VoiceActivityDetector? = null

    // Audio infrastructure (shared with main app)
    private var sharedBuffer: SharedAudioBuffer? = null
    private var audioCaptureService: AudioCaptureService? = null
    private var wakeWordDetector: WakeWordDetectorInterface? = null

    // State
    private var currentState = VoiceState.IDLE
    private var authToken: String? = null
    private var refreshToken: String? = null
    private var isInterceptDisconnecting = false  // True when disconnecting after transcript interception
    private var isEnabled = false

    // Preferences for token caching/refresh
    private val halitePrefs: HalitePreferences by lazy { HalitePreferences(context) }

    // Threading
    private val handler = Handler(Looper.getMainLooper())
    private var processingWatchdogRunnable: Runnable? = null
    private var listeningWatchdogRunnable: Runnable? = null

    /**
     * Silence every reply owner — set by [HaliteVoiceController] to its `stopAllSpeech`.
     * Null when no controller is attached (tests, bench), where [ttsPlayer] is the only owner.
     */
    var onStopAllSpeech: (() -> Unit)? = null

    /** Stop ONLY this service's own player. Called BY `stopAllSpeech` as one of its owners,
     *  so it must never call back into it. */
    fun stopTtsPlayback() {
        ttsPlayer?.stop()
    }
    private var connectWatchdogRunnable: Runnable? = null

    /** Shared 100ms buffer→PCM read loop (see SharedBufferAudioPump). */
    private val audioPump = SharedBufferAudioPump(handler, TAG)

    /** Token refresh / credential re-auth, with the one-shot guard. */
    private val authRecovery: HaAssistAuthRecovery by lazy {
        HaAssistAuthRecovery(halitePrefs, webViewProvider)
    }

    /**
     * What the pipeline we're about to use can actually do. Populated from the pipeline list on the
     * per-turn socket; a cold/stale cache just means "don't block".
     */
    private val pipelineCaps = HaPipelineCapabilityCache()

    /**
     * On-device / engine-direct STT, when the user's STT choice isn't HA's pipeline (or the
     * pipeline can't transcribe). Set by HaliteVoiceController once prepared; null = unavailable,
     * and the service streams audio to HA exactly as before.
     */
    var localSttStage: HaLocalSttStage? = null

    /** One-shot continuation for the pipeline-list pre-flight; cleared as soon as it runs. */
    private var pipelineListPending: (() -> Unit)? = null

    /**
     * Whether the DEVICE speaks this turn's response. Normally [useLocalTts], but a pipeline with
     * no `tts_engine` forces it — otherwise HA fails the run with "does not support text-to-speech"
     * and the turn drops (LocalVoiceSwitch, 2026-07-22).
     */
    private var effectiveLocalTts: Boolean = true

    // Buffer position from wake word detection - used to capture audio spoken during connection delay
    private var wakeWordBufferPosition: Long = 0

    // Fallback TTS: when local TTS fails, play HA pipeline TTS audio instead
    private var pendingFallbackTts = false

    // Configuration
    var useLocalTts: Boolean = true  // Use device TTS instead of HA TTS
    var micMuted: Boolean = false    // When true, ignore wake word detections

    // WS-F.0b AEC render reference for HA proxy playback — set BEFORE initialize().
    var aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null

    // Callbacks
    var onStateChanged: ((VoiceState, String?) -> Unit)? = null  // State + optional subtitle/response
    var onSttResult: ((text: String) -> Unit)? = null
    var onIntentResult: ((intent: String, response: String) -> Unit)? = null
    var onTtsRequest: ((text: String, isCommand: Boolean) -> Unit)? = null  // Called when local TTS should speak (isCommand = device/HA action ack, not an answer)
    /** #64: the ENGINE the device-side reply will actually be spoken with (e.g. `tts.piper`),
     *  for the per-turn settings line. Supplied by the controller, which owns the router.
     *  Null (older wiring / no reply lane) → the line degrades to naming the stage only. */
    var describeTtsEngine: (() -> String)? = null
    var onError: ((error: String) -> Unit)? = null
    var onMicMutedWakeWord: (() -> Unit)? = null  // Called when wake word detected while muted
    var onWakeWordAccepted: (() -> Unit)? = null  // Called when wake word passes all guards and voice interaction starts

    /**
     * Transcript interceptor for local command handling (music, timers, etc.).
     * Called when STT result is available, before HA intent processing completes.
     * Return non-null TTS response string to intercept (HA intent will be ignored).
     * Return null to let HA handle the command normally.
     */
    var onTranscriptInterceptor: ((text: String) -> String?)? = null
    /** Punch #4 (Voice Assist): answer a weather question natively — HA Assist can't do weather
     *  ("no weather is exposed"). Given the parsed WeatherIntercept args, returns the spoken
     *  weather line via WeatherVoiceTool (a brief snapshot read; called off the main thread).
     *  Null = not wired ⇒ weather falls through to HA. */
    var onWeatherQuery: ((args: JSONObject) -> String)? = null
    @Volatile private var transcriptIntercepted = false

    /**
     * Initialize voice service components
     */
    fun initialize() {
        Log.d(TAG, "Initializing HA Voice Service")

        // Create VAD
        vad = VoiceActivityDetector().apply {
            setSilenceTimeout(1500L)  // 1.5 seconds of silence = end of speech
            setMinSpeechDuration(300L) // Wait 300ms before checking silence
            onEndOfSpeech = {
                handleEndOfSpeech()
            }
        }

        // Create TTS player
        ttsPlayer = HaTtsPlayer(context).apply {
            aecControllerProvider = this@HaVoiceService.aecControllerProvider
            onPlaybackStarted = {
                setState(VoiceState.SPEAKING)
            }
            onPlaybackCompleted = {
                handleTtsComplete()
            }
            onPlaybackError = { error ->
                Log.e(TAG, "TTS error: $error")
                handleTtsComplete()
            }
        }

        Log.d(TAG, "Voice service initialized")
    }

    /**
     * Set shared audio buffer (from main voice coordinator)
     */
    fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
    }

    /**
     * Set audio capture service (from main voice coordinator)
     */
    fun setAudioCaptureService(service: AudioCaptureService) {
        this.audioCaptureService = service
    }

    /**
     * Set wake word detector (to pause/resume during voice interaction)
     */
    fun setWakeWordDetector(detector: WakeWordDetectorInterface) {
        this.wakeWordDetector = detector
    }

    /**
     * Enable or disable voice control
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.d(TAG, "Voice control ${if (enabled) "enabled" else "disabled"}")

        if (!enabled) {
            // Stop any ongoing voice interaction
            stopVoiceInteraction()
        }
    }

    /**
     * Handle wake word detection
     * Called when Edge Impulse detects "Hey Dashie"
     */
    fun onWakeWordDetected(confidence: Float, bufferPosition: Long) {
        if (!isEnabled) {
            Log.d(TAG, "Wake word ignored - voice disabled")
            return
        }

        if (micMuted) {
            Log.d(TAG, "Wake word ignored - microphone muted")
            handler.post { onMicMutedWakeWord?.invoke() }
            return
        }

        // A wake is honoured from IDLE (the ordinary start) and from an interruptible
        // mid-turn state (barge-in). LISTENING stays a reject — it would self-trigger on
        // the user's own command audio — so the guard is narrowed, not removed.
        // 🔴 Until 2026-08-26 this was `if (currentState != IDLE) return` at Log.d: a barge
        // that passed every other gate (detector armed, TTS-window branch taken, `BARGE:`
        // at up to 100 %) was discarded one line later, 3/3. Debug-level silence hid it ~8mo.
        val interrupting = HaWakeRearmPolicy.isInterruptible(currentState)
        if (currentState != VoiceState.IDLE && !interrupting) {
            Log.w(TAG, "DROP: wake word ignored in state $currentState — not an interruptible turn state")
            return
        }

        Log.d(TAG, "Wake word detected! Confidence: ${"%.1f".format(confidence * 100)}%")

        if (interrupting) {
            Log.i(TAG, "🎤 Wake word interrupting $currentState — starting new interaction")
            // Reuses the existing teardown rather than restating it — one holder.
            // ⚠️ resumeWakeWord=false is load-bearing: the default posts a detector restart
            // 500 ms out, which would fire mid-LISTENING on the NEW turn and re-create the
            // self-trigger the guard above prevents — the same defect one layer deeper.
            stopVoiceInteraction(resumeWakeWord = false)
        }

        // Notify controller that wake word was accepted (past all guards)
        // so it can duck audio only when a real voice interaction is starting
        onWakeWordAccepted?.invoke()

        // Store buffer position from wake word detection - this captures audio spoken during connection delay
        wakeWordBufferPosition = bufferPosition
        Log.d(TAG, "Stored wake word buffer position: $bufferPosition")

        // Stop wake word detection for the RECORDING window only. [rearmWakeIfListeningIsOver]
        // brings it back as soon as the turn leaves LISTENING, so the user can interrupt the
        // reply — the comment here used to say "during voice interaction" and meant it, which is
        // the hole that made barge-in unreachable in this mode for ~8 months.
        wakeWordDetector?.stop()

        // Extract auth token and start voice flow
        extractAuthTokenAndConnect()
    }

    /**
     * Extract HA auth token from WebView localStorage or cached preferences.
     * Uses HaTokenExtractor.ensureToken() which:
     * 1. First checks for valid cached token in HalitePreferences
     * 2. If expired, tries to refresh using cached refresh token
     * 3. Falls back to extracting from WebView localStorage
     */
    private fun extractAuthTokenAndConnect() {
        setState(VoiceState.CONNECTING)
        authRecovery.resetForNewInteraction()

        // Use HaTokenExtractor to handle token acquisition with caching and refresh
        HaTokenExtractor.ensureToken(webView, halitePrefs) { success ->
            if (success) {
                // Token is now cached in halitePrefs
                authToken = halitePrefs.connection.haAccessToken
                refreshToken = halitePrefs.connection.haRefreshToken.takeIf { it.isNotEmpty() }
                Log.d(TAG, "Auth token acquired (${authToken?.take(20)}...), refresh token: ${if (refreshToken != null) "present" else "missing"}")
                connectToAssistPipeline()
            } else {
                Log.e(TAG, "Failed to acquire HA auth token")
                handleError("Please log in to Home Assistant first")
            }
        }
    }

    /**
     * Connect to HA Assist pipeline
     */
    private fun connectToAssistPipeline() {
        val token = authToken ?: run {
            handleError("No auth token")
            return
        }

        Log.d(TAG, "Connecting to HA Assist pipeline...")
        transcriptIntercepted = false

        // Watchdog — if the connect / auth handshake hasn't completed within
        // CONNECT_TIMEOUT_MS, bail out so the UI doesn't stay stuck on
        // "Connecting…" forever (e.g. TLS hang against an unreachable HA).
        startConnectWatchdog()

        // Create new client
        assistClient = HaAssistClient(haUrl, token).apply {
            onAuthSuccess = {
                cancelConnectWatchdog()
                // Learn what the pipeline can do BEFORE choosing stages. Nothing checked this
                // before, so an STT-less pipeline failed once per wake word with a raw HA string.
                if (pipelineCaps.isFresh()) {
                    planAndStartTurn()
                } else {
                    Log.d(TAG, "Pipeline capabilities unknown — requesting the list first")
                    awaitPipelineListThenStart()
                }
            }

            onPipelinesReceived = { pipelines ->
                pipelineCaps.update(pipelines)
                Log.d(TAG, "Pipeline capabilities cached (${pipelines.size} pipelines)")
                // Fires either as part of the pre-flight below or from an unrelated refresh; the
                // one-shot guard in awaitPipelineListThenStart keeps the turn from double-starting.
                pipelineListPending?.let { it() }
            }

            onAuthFailed = { error ->
                Log.e(TAG, "HA auth failed: $error")
                authRecovery.attempt(
                    onRecovered = { access, refresh ->
                        authToken = access
                        refreshToken = refresh
                        // Disconnect the old client (onDisconnected is suppressed while recovering)
                        assistClient?.disconnect()
                        assistClient = null
                        connectToAssistPipeline()
                    },
                    onGiveUp = { reason -> handleError(reason) },
                )
            }

            onPipelineStarted = { handlerId ->
                Log.d(TAG, "Pipeline started, handler=$handlerId")
                startAudioStreaming()
            }

            onSttStart = {
                Log.d(TAG, "STT started")
            }

            onSttEnd = { rawText ->
                // HA's server already ran its own STT/intent on the raw audio independently, so
                // this only drives Dashie's local display + interception. The ladder itself
                // (wake-strip → native weather → local command → forward) is shared with the
                // local-STT path via HaAssistTranscriptGate — one seam, no hand-mirror.
                Log.d(TAG, "HA STT result: $rawText")
                // Return value ignored: on this path HA is ALREADY processing the intent, so
                // "forward" means "do nothing" and an intercept disconnects instead.
                gateTranscript(rawText)
            }

            onIntentEnd = { intent, response, responseType ->
                Log.d(TAG, "Intent: $intent, Response: $response, type: $responseType")

                // HA "action_done" means a device/HA command was executed (vs a
                // "query_answer" informational reply) — used to decide whether a
                // confirmation tone may replace the spoken response.
                val isCommand = responseType == "action_done"

                // Cancel watchdog - we got a response
                cancelProcessingWatchdog()

                if (transcriptIntercepted) {
                    // Transcript was intercepted for local handling - skip HA's response
                    Log.d(TAG, "🎵 Skipping HA intent response - transcript was intercepted")
                    transcriptIntercepted = false
                } else {
                    onIntentResult?.invoke(intent, response)
                    val action = HaAssistResponsePlanner.plan(response, isCommand, effectiveLocalTts)
                    Log.d(TAG, "Response action: ${action.javaClass.simpleName}")
                    when (action) {
                        is HaAssistResponsePlanner.Action.SpeakOnDevice -> {
                            setState(VoiceState.SPEAKING, action.text)
                            onTtsRequest?.invoke(action.text, action.isCommand)
                        }
                        is HaAssistResponsePlanner.Action.DisplayOnly -> {
                            setState(VoiceState.SPEAKING, action.text)
                            handler.postDelayed({ onLocalTtsComplete() }, 100)
                        }
                        is HaAssistResponsePlanner.Action.AwaitHaAudio ->
                            setState(VoiceState.SPEAKING, action.text)
                    }
                }
            }

            onTtsEnd = { url, mimeType ->
                Log.d(TAG, "TTS ready: $url (pendingFallback=$pendingFallbackTts)")
                if (!effectiveLocalTts || pendingFallbackTts) {
                    pendingFallbackTts = false
                    playTtsResponse(url)
                }
            }

            onRunEnd = {
                Log.d(TAG, "Pipeline run ended (deviceTts=$effectiveLocalTts, state=$currentState)")
                // If using local TTS, completion is handled via onLocalTtsComplete()
                // If using HA TTS (!effectiveLocalTts), completion is handled via HaTtsPlayer callback
                // Only auto-complete if using local TTS and not currently speaking
                if (effectiveLocalTts && currentState != VoiceState.SPEAKING) {
                    handleVoiceInteractionComplete()
                }
            }

            onError = { error ->
                Log.e(TAG, "Pipeline error: $error")
                // Rewrite HA's terse pipeline errors into something the user can act on, and keep
                // a config error up long enough to read (the default 2s is too quick). The
                // TTS-not-configured case is the common one when a device falls back to the HA
                // Assist pipeline whose "default" has no TTS engine set.
                // Either message means our cached capabilities are wrong (or were never fetched),
                // so drop them — the next turn re-reads the list and the pre-flight in
                // planAndStartTurn degrades instead of failing again.
                if (error.contains("does not support", ignoreCase = true)) {
                    pipelineCaps.invalidate()
                }
                if (error.contains("does not support text-to-speech", ignoreCase = true)) {
                    handleError(
                        "This Home Assistant pipeline doesn't have Text-to-Speech configured.",
                        dismissMs = 8000L,
                    )
                } else if (error.contains("does not support speech-to-text", ignoreCase = true)) {
                    handleError(
                        "This Home Assistant pipeline doesn't have Speech-to-Text configured. " +
                            "Pick a different pipeline in Settings → Voice & AI → Voice Pipeline.",
                        dismissMs = 8000L,
                    )
                } else {
                    handleError(error)
                }
            }

            onSttNoText = {
                // STT couldn't recognize speech - show friendly message (not an error)
                Log.d(TAG, "STT no text recognized - showing friendly message")
                cancelProcessingWatchdog()
                val fallbackMessage = "I didn't understand that"
                onIntentResult?.invoke("", fallbackMessage)
                setState(VoiceState.SPEAKING, fallbackMessage)
                // Don't invoke TTS - just show the message briefly and return to idle
                handler.postDelayed({
                    onLocalTtsComplete()
                }, 100)
            }

            onDisconnected = { reason ->
                Log.d(TAG, "Disconnected: $reason")
                // Don't show error if we're intentionally disconnecting (token refresh or
                // transcript interception), OR if we're ALREADY in ERROR — a real pipeline error
                // (e.g. "the pipeline does not support text-to-speech") calls handleError, which
                // disconnects, which fires this callback; reporting "Disconnected: " here would
                // mask the meaningful error the user needs to see. Keep the real one.
                if (currentState != VoiceState.IDLE && currentState != VoiceState.ERROR &&
                    !authRecovery.isRecovering && !isInterceptDisconnecting) {
                    handleError("Disconnected: $reason")
                }
                isInterceptDisconnecting = false
            }
        }

        assistClient?.connect()
    }

    // ── Stage planning ────────────────────────────────────────────────────────

    /**
     * Ask HA for its pipeline list, then plan the turn. Falls through to planning WITHOUT
     * capabilities if the list doesn't arrive in [PIPELINE_LIST_TIMEOUT_MS] — an unknown pipeline
     * must never block a turn.
     */
    private fun awaitPipelineListThenStart() {
        var started = false
        val start = {
            if (!started) {
                started = true
                pipelineListPending = null
                planAndStartTurn()
            }
        }
        pipelineListPending = start
        handler.postDelayed({
            if (!started) {
                Log.w(TAG, "DROP: pipeline list didn't arrive in ${PIPELINE_LIST_TIMEOUT_MS}ms — " +
                    "planning without capabilities")
                start()
            }
        }, PIPELINE_LIST_TIMEOUT_MS)
        assistClient?.requestPipelineList()
    }

    /**
     * Decide which stages HA runs this turn, then start it.
     *
     * Three outcomes: transcribe on-device and send HA text; stream audio for HA to transcribe (the
     * pre-2026-07-29 behavior); or refuse with something the user can act on.
     */
    private fun planAndStartTurn() {
        val pipelineId = halitePrefs.voice.voicePipelineId.takeIf { it.isNotEmpty() }
        val caps = pipelineCaps.capsFor(pipelineId)
        val stage = localSttStage
        val effectiveStt = SttProviderFactory.resolveEffectiveStt(halitePrefs.voice)
        val sttIsLocalByChoice = effectiveStt != VoicePreferences.STT_VA_DEFAULT

        val plan = HaAssistStagePlanner.plan(
            caps = caps,
            sttIsLocalByChoice = sttIsLocalByChoice,
            localSttAvailable = stage?.localSttAvailable() == true,
            prefersLocalTts = useLocalTts,
        )
        effectiveLocalTts = plan.endAtIntent

        if (plan.coercedToLocalStt) {
            Log.w(TAG, "DROP: pipeline '${caps?.name}' has no stt_engine — " +
                "transcribing on-device instead")
        }
        if (plan.coercedToLocalTts) {
            Log.w(TAG, "DROP: pipeline '${caps?.name}' has no tts_engine — " +
                "the device will speak the response")
        }

        plan.error?.let {
            Log.e(TAG, "Cannot run a turn: $it")
            handleError(it, dismissMs = 8000L)
            return
        }

        // 🔴 Report the engine that WILL RUN, never the preference. This line used to print
        // "local ($effectiveStt)" straight off the pref, so a device silently substituting HA's
        // faster_whisper for the user's "On-Device (Built-in)" pick logged `android_voice` —
        // affirmatively false, and it was the one line a debugger would trust.
        // When the two differ the line says BOTH, so a substitution is visible without grepping
        // for the DROP that [HaLocalSttStage.localSttAvailable] now also emits.
        // Compare like with like: resolvedLead is a ProviderType, effectiveStt a SETTING VALUE.
        // settingValueOf translates, so "ANDROID_NATIVE vs android_voice" reads as agreement
        // rather than as a substitution.
        val runningLead = stage?.resolvedLead
        val runningStt = runningLead?.let { SttProviderFactory.settingValueOf(it) ?: it.name }
        val sttStage = when {
            !plan.startAtIntent -> "HA pipeline"
            runningStt == null -> "local (no rung resolved)"
            runningStt == effectiveStt -> "local ($runningStt)"
            else -> "local ($runningStt — SUBSTITUTED, user picked $effectiveStt)"
        }
        // 🔴 #64: same rule as the STT half — report the ENGINE that will run, not the stage.
        // This line used to print `TTS = device`, which is the STAGE (plan.endAtIntent). It was
        // TRUE and useless: it said the device speaks, never WHICH engine — so a `ttsProvider`
        // the lane silently ignored (Piper, 2026-08-26) could not be seen in any log, and the
        // defect had to be found by ear. Naming the resolved engine turns this into the oracle
        // for "is the setting honoured on this lane?" — and it names a degraded SUBSTITUTION
        // too, so free-engine degradation is visible instead of looking like a dishonoured pref.
        // 🔒 Shape pre-agreed with Thread T (s47 cont.1) and enforced by `npm run lint:markers`:
        //     TTS = HA pipeline
        //     TTS = device (<setting value>[: <engine detail>][ — …])
        // Keeps the STAGE word (`device`) and ADDS the engine rather than replacing one with the
        // other — the stage half was never wrong, only insufficient, so instruments already
        // asserting on `TTS = device` keep working while gaining the engine they lacked.
        // ONE line per turn, deliberately: T's three scorer bugs this week were all correlation
        // bugs, and a second line would have to be correlated back to its turn.
        val ttsStage = when {
            !plan.endAtIntent -> "HA pipeline"
            else -> "device (" + (describeTtsEngine?.invoke() ?: "engine unknown — no reply-lane wiring") + ")"
        }
        Log.i(TAG, "STT stage = $sttStage" +
            ", TTS = $ttsStage" +
            ", pipeline=${pipelineId ?: "default"} (${caps?.name ?: "capabilities unknown"})")

        if (plan.startAtIntent && stage != null) {
            transcribeLocallyThenForward(stage, plan, pipelineId)
        } else {
            assistClient?.startAudioPipeline(endAtIntent = plan.endAtIntent, pipelineId = pipelineId)
        }
    }

    /**
     * Local-STT path: transcribe on-device, run the shared transcript gate, and forward TEXT to HA
     * (`start_stage=intent`) only if nothing local claimed the turn.
     *
     * Note what this path does NOT need: the disconnect-to-prevent-double-execute dance the audio
     * path performs. HA hasn't seen the utterance yet, so a local intercept simply never forwards.
     */
    private fun transcribeLocallyThenForward(
        stage: HaLocalSttStage,
        plan: HaAssistStagePlanner.Plan,
        pipelineId: String?,
    ) {
        val buffer = sharedBuffer ?: run {
            handleError("No audio buffer available")
            return
        }
        setState(VoiceState.LISTENING)
        stage.transcribe(buffer, wakeWordBufferPosition) { result ->
            when (result) {
                is HaLocalSttStage.Result.NoSpeech -> {
                    Log.d(TAG, "Local STT: no speech")
                    handleLocalNoSpeech()
                }
                is HaLocalSttStage.Result.Failed -> {
                    Log.e(TAG, "Local STT failed: ${result.error}")
                    handleError(result.error)
                }
                is HaLocalSttStage.Result.Text -> {
                    setState(VoiceState.PROCESSING)
                    startProcessingWatchdog()
                    val decision = gateTranscript(result.text)
                    if (decision != null) {
                        // Nothing local claimed it — HA owns intent/execute/respond, from text.
                        assistClient?.startTextPipeline(
                            text = decision,
                            endAtIntent = plan.endAtIntent,
                            pipelineId = pipelineId,
                        )
                    }
                }
            }
        }
    }

    /**
     * Run the shared transcript gate and execute whatever it decides.
     *
     * @return the text HA should process, or null when the turn was handled locally.
     */
    private fun gateTranscript(rawText: String): String? {
        val decision = HaAssistTranscriptGate.classify(
            rawText = rawText,
            weatherWired = onWeatherQuery != null,
            intercept = { onTranscriptInterceptor?.invoke(it) },
        )
        Log.d(TAG, "Transcript: '${decision.text}' → ${decision.outcome.javaClass.simpleName}")
        onSttResult?.invoke(decision.text)

        when (val outcome = decision.outcome) {
            is HaAssistTranscriptGate.Outcome.Weather -> {
                // HA Assist can't answer weather ("no weather is exposed") — fulfill natively.
                transcriptIntercepted = true
                cancelProcessingWatchdog()
                disconnectForLocalHandling()
                val line = onWeatherQuery?.invoke(outcome.args)
                    ?.ifBlank { null } ?: "I couldn't get the weather right now."
                setState(VoiceState.SPEAKING, line)
                // A weather ANSWER (not a command ack) — speak it in full.
                handler.post { onTtsRequest?.invoke(line, false) }
                return null
            }
            is HaAssistTranscriptGate.Outcome.LocalCommand -> {
                transcriptIntercepted = true
                Log.i(TAG, "🎵 Transcript intercepted for local handling: '${decision.text}'")
                cancelProcessingWatchdog()
                disconnectForLocalHandling()
                if (outcome.response.isNotEmpty()) {
                    setState(VoiceState.SPEAKING, outcome.response)
                    // Local intercepts are timer/music/volume actions — commands.
                    handler.post { onTtsRequest?.invoke(outcome.response, true) }
                } else {
                    handler.post { handleVoiceInteractionComplete() }
                }
                return null
            }
            is HaAssistTranscriptGate.Outcome.ForwardToHa -> return decision.text
        }
    }

    /**
     * Disconnect so HA's server can't ALSO process the intent (duplicate service calls). Only
     * meaningful on the audio path, where a pipeline run is already in flight; harmless otherwise.
     */
    private fun disconnectForLocalHandling() {
        isInterceptDisconnecting = true
        assistClient?.disconnect()
    }

    /** Local STT heard nothing — mirror the friendly, TTS-free HA `stt-no-text` handling. */
    private fun handleLocalNoSpeech() {
        cancelProcessingWatchdog()
        val fallbackMessage = "I didn't understand that"
        onIntentResult?.invoke("", fallbackMessage)
        setState(VoiceState.SPEAKING, fallbackMessage)
        handler.postDelayed({ onLocalTtsComplete() }, 100)
    }

    /**
     * Start streaming audio from SharedAudioBuffer to HA
     */
    private fun startAudioStreaming() {
        setState(VoiceState.LISTENING)

        val buffer = sharedBuffer ?: run {
            handleError("No audio buffer available")
            return
        }

        // Reset VAD for new utterance
        vad?.reset()

        // Read from the WAKE WORD's position (not "now") so speech uttered during the connection
        // delay is included, starting PAST the wake-word tail so HA's Whisper doesn't fold
        // "…dashie" into the command. The skip value is shared with the cascade — this path is
        // always the HA pipeline's buffered Whisper, hence STT_VA_DEFAULT.
        audioPump.start(
            buffer = buffer,
            fromPosition = wakeWordBufferPosition +
                SttProviderFactory.wakeTailSkipSamples(VoicePreferences.STT_VA_DEFAULT),
            maxDurationMs = MAX_RECORDING_DURATION_SEC * 1000L,
            keepRunning = { currentState == VoiceState.LISTENING },
            onMaxDuration = { handleEndOfSpeech() },
        ) { pcmBytes ->
            vad?.processAudio(pcmBytes)
            assistClient?.streamAudio(pcmBytes)
        }
    }

    /**
     * Handle end of speech detected
     */
    private fun handleEndOfSpeech() {
        if (currentState != VoiceState.LISTENING) {
            return
        }

        Log.d(TAG, "End of speech - stopping audio stream")

        audioPump.stop()

        // Signal end of audio to HA
        assistClient?.endAudioStream()

        setState(VoiceState.PROCESSING)

        // Start watchdog timer - if HA doesn't respond in time, timeout
        startProcessingWatchdog()
    }

    /**
     * Start watchdog timer for PROCESSING state
     * If HA doesn't respond within timeout, complete the interaction
     */
    private fun startProcessingWatchdog() {
        // Cancel any existing watchdog
        processingWatchdogRunnable?.let { handler.removeCallbacks(it) }

        processingWatchdogRunnable = Runnable {
            if (currentState == VoiceState.PROCESSING) {
                Log.w(TAG, "Processing watchdog timeout - no response from HA")
                val fallbackMessage = "Sorry, I didn't understand that"
                // Show a "no response" message to user (no TTS - reduces annoyance on false positives)
                onIntentResult?.invoke("", fallbackMessage)
                setState(VoiceState.SPEAKING, fallbackMessage)  // Pass fallback as subtitle
                // Don't invoke TTS for timeout/not-understood - just complete after showing
                handler.postDelayed({
                    onLocalTtsComplete()
                }, 100)
            }
        }

        handler.postDelayed(processingWatchdogRunnable!!, PROCESSING_TIMEOUT_MS)
        Log.d(TAG, "Started processing watchdog (${PROCESSING_TIMEOUT_MS}ms)")
    }

    /**
     * Start the LISTENING watchdog — the third of three, and the one that was missing.
     *
     * 🔴 `CONNECTING` and `PROCESSING` each had a watchdog; `LISTENING` had none, so a turn
     * that never received a transcript hung **indefinitely** — John's stuck orange bar, which
     * only a force-stop cleared. This is not a barge-in accessory: it is reachable by ANY
     * non-finalising STT (the open empty-transcript class already fires on this device), and
     * a barge merely makes it easy to hit, because the interrupted reply talks into the new
     * turn and produces exactly the empty transcript that never finalises.
     *
     * Ends the turn the same way a user cancel does, rather than inventing a new terminal
     * path: full teardown (which now silences every reply owner) and back to IDLE.
     */
    private fun startListeningWatchdog() {
        listeningWatchdogRunnable?.let { handler.removeCallbacks(it) }
        listeningWatchdogRunnable = Runnable {
            if (currentState == VoiceState.LISTENING) {
                Log.w(TAG, "DROP: listening watchdog — no transcript after ${LISTENING_TIMEOUT_MS}ms, " +
                    "ending the turn (a hung LISTENING used to persist until force-stop)")
                stopVoiceInteraction()
            }
        }
        handler.postDelayed(listeningWatchdogRunnable!!, LISTENING_TIMEOUT_MS)
        Log.d(TAG, "Started listening watchdog (${LISTENING_TIMEOUT_MS}ms)")
    }

    /** Cancel the listening watchdog. */
    private fun cancelListeningWatchdog() {
        listeningWatchdogRunnable?.let {
            handler.removeCallbacks(it)
            listeningWatchdogRunnable = null
            Log.d(TAG, "Cancelled listening watchdog")
        }
    }

    /**
     * Cancel the processing watchdog
     */
    private fun cancelProcessingWatchdog() {
        processingWatchdogRunnable?.let {
            handler.removeCallbacks(it)
            processingWatchdogRunnable = null
            Log.d(TAG, "Cancelled processing watchdog")
        }
    }

    /**
     * Start connect watchdog. Fires if we're still in CONNECTING state after
     * CONNECT_TIMEOUT_MS, treating it as a connection failure. Without this,
     * a hung TLS handshake or unreachable HA leaves the UI showing
     * "Connecting…" indefinitely.
     */
    private fun startConnectWatchdog() {
        connectWatchdogRunnable?.let { handler.removeCallbacks(it) }
        connectWatchdogRunnable = Runnable {
            if (currentState == VoiceState.CONNECTING) {
                Log.w(TAG, "⏰ Connect watchdog: still CONNECTING after ${CONNECT_TIMEOUT_MS}ms — aborting")
                handleError("Voice connection timed out")
            }
        }
        handler.postDelayed(connectWatchdogRunnable!!, CONNECT_TIMEOUT_MS)
        Log.d(TAG, "Started connect watchdog (${CONNECT_TIMEOUT_MS}ms)")
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogRunnable?.let {
            handler.removeCallbacks(it)
            connectWatchdogRunnable = null
            Log.d(TAG, "Cancelled connect watchdog")
        }
    }

    /**
     * Play TTS response from HA
     */
    private fun playTtsResponse(ttsUrl: String) {
        val token = authToken ?: return

        // Extract origin (scheme + host + port) from haUrl since the TTS proxy API
        // is at the HA root, not under any dashboard path (e.g. /ha-dashie)
        val origin = try {
            val uri = android.net.Uri.parse(haUrl)
            "${uri.scheme}://${uri.authority}"
        } catch (e: Exception) {
            haUrl.trimEnd('/')
        }

        Log.d(TAG, "Playing TTS response (origin=$origin)")
        ttsPlayer?.play(origin, ttsUrl, token)
    }

    /**
     * Handle TTS playback complete
     */
    private fun handleTtsComplete() {
        Log.d(TAG, "TTS complete")
        handleVoiceInteractionComplete()
    }

    /**
     * Called when local TTS finishes speaking
     * Call this from the activity when device TTS completes
     */
    fun onLocalTtsComplete() {
        Log.d(TAG, "Local TTS complete")
        handleVoiceInteractionComplete()
    }

    /**
     * Called when local TTS is unavailable (engine not initialized or speak failed).
     * Sets a flag so the HA pipeline's TTS audio will be played as fallback
     * when onTtsEnd fires. If HA pipeline TTS isn't configured, a timeout
     * ensures the interaction still completes (just without speech).
     */
    fun onLocalTtsFailed() {
        Log.w(TAG, "Local TTS failed - will use HA pipeline TTS as fallback")
        pendingFallbackTts = true

        // Timeout: if HA pipeline TTS doesn't arrive within 3s, complete without speech
        handler.postDelayed({
            if (pendingFallbackTts) {
                Log.w(TAG, "HA pipeline TTS fallback timeout - completing without speech")
                pendingFallbackTts = false
                onLocalTtsComplete()
            }
        }, 3000)
    }

    /**
     * Handle voice interaction complete - return to idle
     */
    private fun handleVoiceInteractionComplete() {
        Log.d(TAG, "Voice interaction complete")

        // Cancel watchdog
        cancelProcessingWatchdog()

        // Disconnect from HA
        assistClient?.disconnect()
        assistClient = null

        setState(VoiceState.IDLE)

        // Resume wake word detection
        handler.postDelayed({
            wakeWordDetector?.start()
        }, 500)
    }

    /**
     * Handle error during voice interaction. [dismissMs] controls how long the error stays on
     * screen before returning to idle — a config error (e.g. HA has no pipeline TTS) needs longer
     * to read than a transient blip.
     */
    private fun handleError(error: String, dismissMs: Long = 2000L) {
        Log.e(TAG, "Voice error: $error")

        // Cancel watchdogs
        cancelProcessingWatchdog()
        cancelConnectWatchdog()

        // Stop streaming
        audioPump.stop()
        localSttStage?.cancel()

        // Stop TTS
        ttsPlayer?.stop()

        // Disconnect
        assistClient?.disconnect()
        assistClient = null

        setState(VoiceState.ERROR, error)  // Pass error message as subtitle for display
        onError?.invoke(error)

        // Return to idle after delay
        handler.postDelayed({
            setState(VoiceState.IDLE)
            wakeWordDetector?.start()
        }, dismissMs)
    }

    /**
     * Stop any ongoing voice interaction
     */
    /**
     * @param resumeWakeWord post the usual 500 ms detector restart. Default true keeps every
     *   existing caller behaving exactly as before; barge-in passes false because the new turn
     *   it starts owns the detector, and a queued restart would fire during its LISTENING phase.
     */
    fun stopVoiceInteraction(resumeWakeWord: Boolean = true) {
        Log.d(TAG, "Stopping voice interaction")
        pendingFallbackTts = false

        // Cancel watchdogs
        cancelProcessingWatchdog()
        cancelConnectWatchdog()

        // Stop streaming
        audioPump.stop()
        localSttStage?.cancel()

        // Stop TTS — EVERY owner, not just this service's player. An HA-Assist reply is
        // rendered by the CONTROLLER (devicePcmSpeaker / the device TTS engine), so
        // ttsPlayer?.stop() alone silences the one player that is usually not speaking and
        // leaves the actual answer talking into the next turn's LISTENING (measured at
        // vc189). onStopAllSpeech routes to HaliteVoiceController.stopAllSpeech, which owns
        // the full owner list; this call stays for the case where no controller is attached.
        ttsPlayer?.stop()
        onStopAllSpeech?.invoke()

        // Disconnect
        assistClient?.disconnect()
        assistClient = null

        setState(VoiceState.IDLE)

        // Resume wake word detection. The wake word is stopped at wake time and, until now,
        // only handleVoiceInteractionComplete restarted it — so a cancel (X, or an early
        // exit mid-listen) left the wake word dead until app restart in VA mode (observed
        // on-device 2026-07-06). Mirror the completion path.
        if (resumeWakeWord) {
            handler.postDelayed({ wakeWordDetector?.start() }, 500)
        }
    }

    /**
     * Set voice state and notify callback
     * @param newState The new voice state
     * @param subtitle Optional subtitle/response text to display (used for SPEAKING state)
     */
    private fun setState(newState: VoiceState, subtitle: String? = null) {
        if (currentState != newState) {
            Log.d(TAG, "State: $currentState -> $newState" + (subtitle?.let { " (subtitle: $it)" } ?: ""))
            currentState = newState
            rearmWakeIfListeningIsOver(newState)
            // Arm/disarm the LISTENING watchdog here, not at the two setState(LISTENING)
            // call sites — same reason the re-arm hangs off this transition: two call sites
            // means two copies of one rule, and a third entry added later would inherit
            // neither. Leaving LISTENING by ANY route cancels it.
            if (newState == VoiceState.LISTENING) startListeningWatchdog() else cancelListeningWatchdog()
            handler.post {
                onStateChanged?.invoke(newState, subtitle)
            }
        }
    }

    /**
     * Bring the wake detector back the moment RECORDING is over — not when the whole interaction is.
     *
     * The stop in [startVoiceInteraction] used to last the *entire* interaction, with the only
     * restart on completion + 500 ms. Measured on device 2026-08-26: nothing was listening for
     * **5.15–5.18 s** (4/4). So "Hey Dashie" over a reply could not be heard, and the `BARGE:`
     * gate in `DualEngineDetector` was structurally unreachable here — the capability existed and
     * no audio ever reached it.
     *
     * 🔴 The mode next door had already solved this and said so: `VoicePipelineCoordinator` stops
     * only *"during STT recording"* and restarts as soon as a transcript lands, *"so user can
     * interrupt during PROCESSING or SPEAKING"*. This is that lifecycle, not a new mechanism.
     * ⚠️ It is only half the fix — see `HaliteVoiceController`'s `isTtsSpeaking`, which had to
     * learn about this mode too or a live detector would still be routed to the wrong branch.
     *
     * Hung off the state machine, not the transcript callbacks: there are two transcript-arrival
     * points and both enter `PROCESSING`, so hooking those would be a second copy of one rule and
     * a third path would silently miss it. `start()` is idempotent while running, so the
     * completion-path restart stays as the backstop it always was.
     */
    private fun rearmWakeIfListeningIsOver(newState: VoiceState) {
        if (!HaWakeRearmPolicy.shouldRearmOnEntering(newState)) return
        if (wakeWordDetector?.isDetecting() == true) return
        Log.i(TAG, "Wake detector re-armed at $newState — the user can interrupt from here " +
                "(barge-in requires something to be listening during playback)")
        handler.post { wakeWordDetector?.start() }
    }

    /**
     * Get current voice state
     */
    fun getState(): VoiceState = currentState

    /**
     * Shutdown voice service
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down voice service")

        stopVoiceInteraction()
        ttsPlayer?.release()
        ttsPlayer = null
        localSttStage?.release()
        localSttStage = null
        vad = null
        authToken = null
    }
}

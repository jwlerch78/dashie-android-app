package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.SupabaseTokenExtractor
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.util.StableDeviceId
import com.dashieapp.Dashie.halite.voice.stt.*
import com.dashieapp.Dashie.voice.realtime.ConversationEngine
import com.dashieapp.Dashie.voice.realtime.GeminiLiveEngine
import com.dashieapp.Dashie.voice.realtime.RealtimeCalendarBridge
import com.dashieapp.Dashie.voice.realtime.RealtimeConfig
import org.json.JSONObject
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface

/**
 * Voice Pipeline Coordinator
 *
 * Orchestrates the complete voice flow for Dashie Kiosk with overlay NLP:
 *
 * 1. Wake word detection ("Hey Dashie") via pluggable engine (Edge Impulse or microWakeWord)
 * 2. STT via pluggable providers (HA Assist, Deepgram)
 * 3. NLP via overlay's processVoiceCommand (timer commands, HA control, LLM)
 * 4. TTS via device TextToSpeech
 *
 * This coordinator supports multiple STT pathways:
 * - HA Assist: Local Whisper STT via Home Assistant
 * - Deepgram: Cloud STT for faster/more accurate transcription
 *
 * The NLP is always handled by the overlay, enabling:
 * - Local timer commands (fast, no network)
 * - Home Assistant commands (via HA API)
 * - General queries (via LLM proxy in HA addon)
 *
 * Configuration:
 * - STT provider: Set via setSttProvider() or auto-selected based on availability
 * - Fallback: If primary STT fails, can fall back to secondary
 */
class VoicePipelineCoordinator(
    private val context: Context,
    private val webViewProvider: () -> WebView,
    private val halitePrefs: HalitePreferences
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()

    /**
     * The brain/STT `endpoint_id` for THIS device — the per-device stable id.
     *
     * MUST be [StableDeviceId], not `Build.MODEL` (a model bucket that collides
     * across same-model devices) and not raw `Settings.Secure.ANDROID_ID`
     * (which was NOT stable on some ROMs / across factory resets — StableDeviceId
     * caches the first-seen value in SharedPrefs so it stays put). This is the
     * SAME value the webapp registers as `user_devices.device_id`
     * (JS getDeviceId() → nativeInfo.androidId → DeviceInfoHelper → StableDeviceId.read)
     * and what `DashieNative.getDeviceId()` returns. Keeping all three identical is
     * what lets the brain resolve this device's per-device voice settings
     * (personality, etc.) from user_devices, keyed by endpoint_id == device_id.
     * Do not "simplify" this back to Build.MODEL/ANDROID_ID.
     */
    private val endpointId: String
        get() = StableDeviceId.read(context)
    companion object {
        private const val TAG = "VoicePipeline"
        // Wake-word tail skip now lives in SttProviderFactory.wakeTailSkipSamples — HA Voice
        // Assist's local STT stage feeds the same providers from the same buffer, and two copies
        // of the 100ms/280ms rule would drift.
        // Deepgram endpointing (speech_final silence): snappy for commands, patient for the back-and-forth.
        private const val DEFAULT_ENDPOINTING_MS = 400
        private const val CASCADE_ENDPOINTING_MS = 1000
        // Gap after a silent in-dialog command before re-listening, so the confirmation tone
        // finishes and isn't captured as the follow-up (DLG-6 in-dialog HA commands).
        private const val CASCADE_POSTCOMMAND_RELISTEN_MS = 700L
        // Delay before the one dialog STT retry — lets a 502/cold-start clear and the failed
        // attempt's fallback-chain stragglers drain (an instant retry lost that race, FB24).
        private const val CASCADE_RETRY_DELAY_MS = 1200L
        // FB19: delay before the single no-speech "patience" re-listen — lets the just-ended
        // STT socket finish teardown before the new session opens (an instant restart raced it).
        // FB19: patience budget to START speaking before a silent turn gives up.
        private const val NO_TRANSCRIPT_TIMEOUT_MS = 6000L
        // Out-of-credits wake gate: how often one attempt is let through to the server so a
        // refill can heal the cached not-spendable state (stale-negative recovery, CR4).
        private const val CREDIT_PROBE_INTERVAL_MS = 30_000L
    }

    // Voice state
    enum class PipelineState {
        IDLE,           // Waiting for wake word
        CONNECTING,     // Connecting to STT provider
        LISTENING,      // Recording user speech
        PROCESSING,     // Processing command (NLP)
        SPEAKING,       // Playing TTS response
        ERROR           // Error state
    }

    // Components
    private var sttManager: SttProviderManager? = null
    private var voiceOverlayBridge: VoiceOverlayBridge? = null
    private var vad: VoiceActivityDetector? = null

    // Audio infrastructure
    private var sharedBuffer: SharedAudioBuffer? = null
    private var audioCaptureService: AudioCaptureService? = null
    private var wakeWordDetector: WakeWordDetectorInterface? = null

    // AI lane → cloud brain via the integration gateway (native; avoids WebView CORS).
    // Capability-gated tool list: the brain omits music/video_feeds from the prompt when this
    // device can't fulfill them, so the model never offers a capability we don't have.
    private val brainConverseClient = BrainConverseClient(
        clientToolsProvider = { DeviceToolCapabilities.clientFulfilledTools(halitePrefs) },
    )
    // Device-fulfilled weather: fully NATIVE since 2026-07-19 (WeatherVoiceTool —
    // VoiceWeatherSnapshot reading + vector-pinned WeatherTemplate synthesis); the lambda
    // below hands it to BrainToolResolver. No WebView hop, works identically on kiosk.
    // Device-fulfilled calendar: the brain hands back client_tool:'calendar'; we run the
    // on-device JS calendar tool (window.dashieCalendarTool → merged multi-provider events
    // → deterministic synthesis) and speak its line + render its card. The JS voice-command
    // -router does this in single/web mode, but dialog mode owns the turn natively — so
    // without this, calendar queries leak the raw info_request JSON → "didn't catch that".
    // Same bridge the realtime engine uses. Build plan §0 / FB13.
    private val calendarBridge = RealtimeCalendarBridge(webViewProvider)
    // Device-fulfilled tool execution + spoken-line resolution (weather/calendar/music/cameras/
    // schedule) → BrainToolResolver: injected bridges, no pipeline state, same shape as
    // MultiTurnDispatcher. Call it OFF the main thread — it blocks on the WebView bridges.
    private val brainToolResolver = BrainToolResolver(
        weatherQuery = { WeatherVoiceTool.query(context, it) },
        calendarBridge = calendarBridge,
        useLocalBrain = { halitePrefs.voice.effectiveUseLocalBrain(halitePrefs.householdAnswersGovern) },
    )
    // HA entities: the voice-controllable entity set (getVoiceEntityIds honoring entitySource +
    // entitiesForBrain + device area) the JS fast path already sends the brain. converseBrain used
    // to omit it, so device commands that fell through the fast path had no entities to act on
    // → "couldn't get an answer right now" (20260717). Same webViewProvider bridge pattern.
    private val haEntitiesBridge =
        com.dashieapp.Dashie.voice.realtime.RealtimeHaEntitiesBridge(webViewProvider)
    // HA fast-path (20260717_HA_ENTITY_EXPOSURE_CONTRACT §4): a device command for a KNOWN exposed
    // entity is run through HA's own Assist (conversation/process) BEFORE the brain — HaEntityMatcher
    // gates it against the same ha_entities[] the brain sees, then this executes it on the HA side.
    // Skips the LLM entirely (no latency/cost, no reliance on the brain's entity provisioning).
    private val haAssistConverse =
        com.dashieapp.Dashie.voice.realtime.HaAssistConverse { halitePrefs }
    // WS-D.1: a $0-degraded QUESTION → HA's conversation agent (or a graceful decline), never the
    // cloud brain (which declines → re-pops the chooser). Stateless; blocks, so call off-main.
    private val degradedQuestionHandler = DegradedQuestionHandler(haAssistConverse)

    // Calendar-color (20260711): the cascade calendar pre-flight blocks on the WebView
    // bridge, and runBrainTurn's call sites don't guarantee a background thread — a
    // dedicated single-thread executor keeps it off main (a blocked main deadlocks the
    // bridge's evaluateJavascript round-trip) and serializes turns naturally.
    private val brainPreflight = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Resolves the Deepgram WS Bearer (account JWT when logged in, else a minted /session
    // token for anonymous kiosks). Created in initialize(); drives DeepgramSttProvider.
    private var sttCredentialProvider: SttCredentialProvider? = null

    /** The cascade AEC (owned by HaliteVoiceController). The pipeline engages it for the
     *  duration of a turn so STT/VAD read noise-suppressed audio — see startSttSession. */
    var aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null

    // Realtime "conversation mode" (on-demand S2S layered on top of the cascade,
    // build plan §3.1). Created lazily on first trigger. `inConversation` guards
    // the pipeline while the realtime engine owns the mic.
    private var conversationEngine: ConversationEngine? = null
    private var inConversation = false
    // A Live session opened speculatively at wake (in parallel with STT) that hasn't
    // been committed yet — committed via beginWithText if the query is conversational,
    // or stopped if it's a local command.
    private var speculativeOpen = false

    // State
    private var currentState = PipelineState.IDLE
    private var isEnabled = false
    private var micMuted = false
    // Is the EFFECTIVE STT provider cloud Deepgram? (set in initialize from the same
    // resolution the provider priority uses). Drives the CR4 wake-time credit gate —
    // local/HA-Assist STT is never metered, so it never gates.
    private var usingCloudStt = false
    /** The user's configured STT priority (set at init). Degraded mode swaps in a FREE-only
     *  chain and restores this on refill — so a degraded turn's error-fallback can never leak
     *  to a billed engine (found on-device 2026-07-20: an offline local Whisper fell back to
     *  Deepgram → 402 → chooser). */
    private var configuredSttPriority: List<com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType> = emptyList()

    // WS-D.1 $0 degradation — state + policy live in DegradedVoiceMode (this file is at its
    // size budget). Runtime-only: no pref is written, so nothing needs undoing on refill.
    private val degraded by lazy {
        DegradedVoiceMode(halitePrefs) { type -> sttManager?.getProvider(type)?.isAvailable() == true }
            .also { it.onChanged = { on -> handler.post { onDegradedChanged?.invoke(on) } }
                    it.onAnnounce = { line -> handler.post { onSpeakDegradedNotice?.invoke(line) } }
                    // Capability lease (#65) drives degradation from outside this file, via a
                    // static sink — same reason BrainRouteApplier exists: this file is at budget.
                    DegradedVoiceSink.enterFn = { reason, expiry -> it.enter(reason, expiry) }
                    DegradedVoiceSink.clearFn = { full -> it.clear(full) } }
    }
    /** Fired when degraded mode turns on/off, so the dashboard pill + control-center card can
     *  show a persistent reason. */
    var onDegradedChanged: ((degraded: Boolean) -> Unit)? = null
    /** Speaks the one-time degraded notice through the FREE TTS the plan picked. */
    var onSpeakDegradedNotice: ((String) -> Unit)? = null
    // Last time the out-of-credits wake gate let a probe attempt through (CR4 recovery).
    private var lastCreditProbeMs = 0L

    // Threading
    private val handler = Handler(Looper.getMainLooper())
    private var streamingRunnable: Runnable? = null
    private var recordingStartTime: Long = 0
    private var streamReadPosition: Long = 0
    // Latch so end-of-speech is handled ONCE per turn — the VAD fires onEndOfSpeech
    // every audio chunk past the silence threshold, which was re-calling
    // endAudioStream and resetting the final-result watchdog (the "VAD waits 3s" bug).
    @Volatile private var endOfSpeechHandled = false
    // Wave 2 stage timing: when the user's audio ended (handleEndOfSpeech) — the
    // anchor for stt_ms = audio-end → final transcript. 0 = no anchor this turn
    // (e.g. Deepgram speech_final beat the VAD); consumed (reset) at report time.
    @Volatile private var sttAudioEndAtMs = 0L

    // FB19: has any transcript arrived this window? Gates the VAD + no-transcript timeout.
    @Volatile private var receivedTranscript = false
    private var noTranscriptTimeoutRunnable: Runnable? = null

    // Incremented on each new STT session. A turn's async callbacks (overlay NLP,
    // brain converse) capture the value at start and bail if it no longer matches —
    // so an interrupted turn's late response (e.g. a slow local-LLM reply arriving
    // 20s after the user said the wake word again) can't surface on the new turn.
    private var turnGeneration = 0
    // Scheduled AI-turn (WS5-a): a fired callback is a one-way ANNOUNCEMENT, not a
    // conversation — force single-shot + never re-arm the mic. Turn-scoped: set on
    // inject, snapshot-and-cleared at turn start so it can't leak to a live turn.
    @Volatile private var scheduledAnnouncement = false

    // Configuration
    private val MAX_RECORDING_DURATION_SEC = 10

    // Callbacks
    var onStateChanged: ((PipelineState, String?) -> Unit)? = null
    var onSttResult: ((text: String) -> Unit)? = null
    var onTtsRequest: ((text: String, isCommand: Boolean) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null
    var onMicMutedWakeWord: (() -> Unit)? = null
    var onWakeWordAccepted: (() -> Unit)? = null  // Called when wake word passes all guards
    var onWakeWordInterrupting: (() -> Unit)? = null  // Called when wake word interrupts SPEAKING/PROCESSING
    // Wake-word barge-in inside a cascade Dialog: stop the reply PLAYBACK only (keep the
    // conversation overlay + music duck — unlike onWakeWordInterrupting's full teardown).
    var onDialogBargeIn: (() -> Unit)? = null
    // Live brain progress ("Searching the web…", "Finalizing…") streamed by BrainConverseClient
    // during a tool 2-pass. The controller routes it to the thinking indicator (single) OR the
    // dialog overlay status (dialog) — each self-guards on mode, so wiring calls both. Fires on
    // an OkHttp thread; the controller methods post to the UI thread.
    var onBrainProgress: ((String) -> Unit)? = null
    // CR4 State 3: voice can't run (cloud STT + out of credits) — controller shows the
    // mic-muted-style toast. The CR2 prompt rides CreditStateHolder.onExhausted.
    var onVoiceUnavailable: (() -> Unit)? = null
    // FB19: silent listen window closed — controller shows a subtle small-card notice.
    var onNoSpeechNotice: (() -> Unit)? = null
    // HA fast-path confirmation → the native HA command card (VoiceIndicatorController.showHaCommandResult),
    // the SAME widget the JS 'ha' toast draws. Presented instead of the conversation overlay for a
    // device command handled locally by HA Assist.
    var onHaCommandResult: ((message: String, command: String) -> Unit)? = null
    // Locally-intercepted MUSIC command ack ("Playing music by …") → compact voice notice, the
    // native twin of full mode's small success toast. Without this the ack drew the standard
    // response surface (what a kiosk user reads as "the full voice overlay") for a mere play/skip.
    var onLocalMusicResult: ((message: String, command: String) -> Unit)? = null

    // Realtime conversation mode UI (build plan §3.1) — drives the transcript panel
    // + the listening/thinking indicator, separate from the cascade's onStateChanged.
    var onConversationStart: (() -> Unit)? = null
    var onConversationState: ((ConversationEngine.State) -> Unit)? = null
    var onConversationTranscript: ((ConversationEngine.Speaker, String, String?, Boolean) -> Unit)? = null
    var onConversationCard: ((org.json.JSONObject) -> Unit)? = null
    var onConversationEnd: ((idleClose: Boolean) -> Unit)? = null

    // Speak a NATIVE-owned AI-lane response (single brain / cascade Dialog). Distinct from
    // onTtsRequest (which the overlay path skips in cloud mode assuming JS speaks) — here
    // native owns the response, so it's ALWAYS voiced via the account's cloud voice. onDone
    // fires when playback finishes → drives the precise cascade follow-up re-arm.
    // voiceId: the brain's resolved personality voice (D3); null → default voice.
    // sessionId: the brain conversation_id this response belongs to → the TTS usage row is
    // tagged with it so the console groups the spoken reply under the same AI interaction.
    var onSpeakResponse: ((text: String, voiceId: String?, voiceProvider: String?, sessionId: String?, onDone: () -> Unit) -> Unit)? = null

    /**
     * Initialize the voice pipeline
     */
    suspend fun initialize() {
        Log.i(TAG, "Initializing voice pipeline coordinator")

        // Build + register every STT provider (see SttProviderFactory for the per-provider tuning).
        val providers = SttProviderFactory.createProviders(
            halitePrefs, endpointId, context, androidSttMicHandoff)
        sttManager = providers.manager
        sttCredentialProvider = providers.credentialProvider

        // Anon kiosk: refresh capability + agentMode cache at init (see VoiceSessionAccess).
        // Passes context so the anon-kiosk probe can hard-apply the account's voice
        // config (kiosk voice-config mirror, Phase 1) and reinit if it changed.
        // MUST run between createProviders and resolvePriority — it can rewrite the voice prefs
        // that the priority decision reads.
        VoiceSessionAccess.refreshKioskCapability(context, halitePrefs)

        // Set provider priority based on STT provider setting
        val priority = SttProviderFactory.resolvePriority(halitePrefs.voice)
        usingCloudStt = priority.usingCloudStt
        configuredSttPriority = priority.order   // restored when degraded mode clears
        sttManager?.setProviderPriority(priority.order)
        Log.i(TAG, "STT priority: ${priority.description}")

        // Create voice overlay bridge
        voiceOverlayBridge = VoiceOverlayBridge(webViewProvider).apply {
            onTtsRequest = { text ->
                // Overlay/JS-driven speech (answers, prompts) — not a command ack.
                this@VoicePipelineCoordinator.onTtsRequest?.invoke(text, false)
            }
            onActionRequest = { action ->
                // Handle special actions (e.g., show weather overlay)
                Log.i(TAG, "Voice action: $action")
            }
            onError = { error ->
                handleError(error)
            }
        }

        // Inject voice response listener into parent page
        // This listener catches 'voice-response' postMessages from overlay iframe
        voiceOverlayBridge?.injectVoiceResponseListener()

        // VAD end-of-speech backup (Deepgram endpointing is primary). 1200ms (up from 900ms,
        // John 2026-07-06) so a beat-long mid-sentence pause isn't cut off.
        vad = VoiceActivityDetector().apply {
            setSilenceTimeout(1200L)
            setMinSpeechDuration(300L)
            onEndOfSpeech = {
                onVadEndOfSpeech()
            }
            // The no-transcript timeout asks "did the user say ANYTHING?". The moment the VAD
            // says yes, that question is answered — disarm it and let the utterance run to the
            // MAX_RECORDING_DURATION_SEC cap (which flushes properly). Without this, a BUFFERED
            // provider (local Whisper / HA engine-direct — they emit no interim transcript
            // until they endpoint) had its turn guillotined at 6 s mid-sentence and the audio
            // thrown away. Streaming providers (Deepgram) were immune: their interim result
            // already cancels it. John, 2026-07-13.
            onSpeechStarted = {
                if (!receivedTranscript) {
                    Log.d(TAG, "VAD heard speech — dropping the silent-window timeout")
                    handler.post { cancelNoTranscriptTimeout() }
                }
                // Speech onset in a dialog follow-up → keep the overlay open past the 8s deadline.
                // ⚠️ ENERGY VAD (RMS): a door/cough deletes BOTH dialog stops for the rest of the
                // session. Intentional here; [cascadeAbsoluteTimer] is the backstop that covers it.
                if (cascadeDialogActive) handler.post { cancelCascadeIdle(); cascadeWindowDeadlineMs = 0L }
            }
        }

        Log.i(TAG, "Voice pipeline initialized")
    }

    /**
     * Set shared audio buffer (from audio capture service)
     */
    fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
    }

    /**
     * Set audio capture service
     */
    fun setAudioCaptureService(service: AudioCaptureService) {
        this.audioCaptureService = service
    }

    /**
     * Set wake word detector
     */
    fun setWakeWordDetector(detector: WakeWordDetectorInterface) {
        this.wakeWordDetector = detector
    }

    /**
     * Enable or disable voice control
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.i(TAG, "Voice pipeline ${if (enabled) "enabled" else "disabled"}")

        if (!enabled) {
            stopVoiceInteraction()
        }
    }

    /**
     * Set microphone muted state
     */
    fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }

    /**
     * Handle wake word detection
     */
    fun onWakeWordDetected(confidence: Float, bufferPosition: Long) {
        if (!isEnabled) {
            Log.d(TAG, "Wake word ignored - pipeline disabled")
            return
        }

        if (micMuted) {
            Log.d(TAG, "Wake word ignored - microphone muted")
            handler.post { onMicMutedWakeWord?.invoke() }
            return
        }

        // A time-boxed temp fallback ("Not now" → 24h) that has elapsed: drop it and re-arm the
        // chooser so the user is asked again. A no-expiry "Don't show again" degrade never expires;
        // "Use local voice" persists and is never a runtime degrade at all. (Punch #5)
        if (degraded.isExpired()) {
            Log.i(TAG, "💳 temporary local-voice fallback expired — re-arming the out-of-credits chooser")
            degraded.clear(full = true)
        }

        // CR4 State 3 (credit boundary) at $0 on cloud STT. Behavior (refined on-device
        // 2026-07-20, John): show the CR2 chooser the FIRST time; once the user defers to local
        // voice ("Not now" 24h / "Don't show again", degraded.isActive) HONOR it and stop nagging —
        // the turn runs on free engines and the chooser never reappears until credits return.
        if (!CreditStateHolder.spendable && usingCloudStt) {
            if (degraded.isActive) {
                // User chose local voice — run free, no chooser. Deliberately NO paid probe:
                // a paid retry 402s → markExhausted → the chooser popped back every ~30s,
                // undoing the choice (the bug this replaces). A refill is detected instead by
                // a QUIET balance refresh — spendable flips → the else-branch below leaves
                // degraded on the next wake. Nothing was written, so there's nothing to restore.
                com.dashieapp.Dashie.edition.EditionSeams.credits(context).refreshBalance()
                // fall through — startSttSession uses the degraded free chain.
            } else {
                // First $0 wake (not chosen yet): show the chooser, with a once-per-interval
                // stale-cache heal probe (a real refill lets a paid attempt succeed and flips
                // the cache back; a genuine $0 attempt's STT 402 re-shows the chooser).
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastCreditProbeMs > CREDIT_PROBE_INTERVAL_MS) {
                    lastCreditProbeMs = nowMs
                    Log.i(TAG, "💳 Out-of-credits probe — letting one attempt re-check the server")
                    // fall through — a paid attempt
                } else {
                    Log.i(TAG, "💳 Out of credits — showing the add-credits / use-local-voice chooser")
                    handler.post { onVoiceUnavailable?.invoke() }
                    CreditStateHolder.markExhausted()
                    return
                }
            }
        } else if (CreditStateHolder.spendable) {
            degraded.clear(full = true)   // credits back — nothing was written, so nothing to restore
        }

        // Re-probe the add-on brain route now (during STT), TTL-gated — so a just-added
        // API key routes to the on-prem brain on THIS turn without an app restart (Open
        // Brain §5). The result is cached before converse reads effectiveUseLocalBrain.
        VoiceSessionAccess.refreshBrainRouteIfStale(halitePrefs)

        // Warm-start (A): boot the brain isolate now (during STT) so the converse doesn't cold-start.
        halitePrefs.connection.let { brainConverseClient.warmup(it.supabaseJwt.takeIf { _ -> it.hasSupabaseJwt && !it.isSupabaseJwtExpired }, halitePrefs.voice.effectiveUseLocalBrain(halitePrefs.householdAnswersGovern)) }

        if (cascadeDialogActive) {
            // Wake-word barge-in inside a cascade Dialog (WS-A.1): the wake word is the
            // interruption mechanism — cut a long reply or a slow brain turn and re-open the
            // follow-up mic, KEEPING the conversation (overlay, history, conversation_id).
            // Mirrors Live's flush-playback + re-listen semantics. While a follow-up window
            // is already open the mic is live, so a detection is just noise — ignore it (the
            // spoken "hey dashie ..." lands in the STT stream; cascadeDialogTurn strips it).
            if (currentState == PipelineState.LISTENING || currentState == PipelineState.CONNECTING) {
                Log.d(TAG, "Wake word ignored — dialog follow-up window already open")
                return
            }
            Log.i(TAG, "🎤 Wake word barge-in during cascade Dialog ($currentState)")
            handler.post {
                if (!cascadeDialogActive) return@post   // dialog ended while this was queued
                turnGeneration++              // in-flight brain callback bails on gen mismatch
                awaitingCascadeTts = false    // pending TTS onDone + 30s safety net become no-ops
                stopAudioStreaming()
                sttManager?.cancelSession()
                onDialogBargeIn?.invoke()     // stop reply playback (runs inline — main thread)
                cascadeReListen()             // fresh 8s window, live buffer head
            }
            return
        }

        if (inConversation) {
            // The realtime engine owns the mic during a Live conversation (it has its own
            // barge-in spotter on the AEC-cleaned mic); guard belt-and-suspenders.
            Log.d(TAG, "Wake word ignored - conversation mode active")
            return
        }

        if (currentState == PipelineState.LISTENING || currentState == PipelineState.CONNECTING) {
            Log.d(TAG, "Wake word ignored - already in state $currentState")
            return
        }

        // If currently speaking or processing, interrupt and start new interaction
        val interruptedSpeech = currentState == PipelineState.SPEAKING || currentState == PipelineState.PROCESSING
        if (interruptedSpeech) {
            Log.i(TAG, "🎤 Wake word interrupting $currentState — starting new interaction")
            stopAudioStreaming()
            sttManager?.cancelSession()
            voiceOverlayBridge?.cancelAllPendingCommands()
            // Cancel any pending idle transition timers
            handler.removeCallbacksAndMessages(null)
            // Notify controller to dismiss any open sidebar/result card
            handler.post { onWakeWordInterrupting?.invoke() }
        }

        Log.i(TAG, "Wake word detected! Confidence: ${"%.1f".format(confidence * 100)}%")

        // Notify controller that wake word was accepted (past all guards)
        onWakeWordAccepted?.invoke()

        // Always-mode: speculatively open the Live socket NOW, in parallel with STT,
        // so it's warm by the time we classify (hides the ~1.5s open). It takes no
        // mic — STT keeps the shared capture; we commit (beginWithText) or cancel
        // (stop) after STT classifies. Local commands → cascade as usual. §3.1
        // Only pre-warm a Live socket for agentMode='live'. Dialog/single use the cascade
        // (no socket to warm) → routed at the ai_lane branch below. Build plan §EngineB(C).
        if (effectiveAgentMode() == "live") {
            openSpeculativeConversationEngine()
        }

        // Start STT a short way PAST the wake-word point so the "Hey Dashie" tail doesn't bleed in
        // as a stray "T" (too-short path); users pause before the command so ~100ms is safe.
        // EXCEPT when the wake interrupted Dashie's own speech: the ring buffer behind the wake
        // point is dominated by the still-playing TTS (the wake often fires ON it) — replaying
        // it transcribed Dashie's own reply as the user's command (self-hearing, 2026-07-12).
        // Anchor at the LIVE head; the interrupt already stopped playback.
        // Buffered whisper STT gets the wider skip (see the constants) — Deepgram keeps 100ms.
        // Shared with HA Voice Assist's local STT stage — same providers, same buffer, so the skip
        // lives in SttProviderFactory rather than being mirrored per pipeline.
        // Keyed on the provider that will RUN, not the preference (see
        // SttProviderFactory.WHOLE_CLIP_DECODERS): any unavailable lead — sherpa on a model-less
        // flavor, an unconfigured own-box Whisper — falls to a differently-classed decoder and
        // silently took the wrong skip here too.
        val tailSkip = SttProviderFactory.wakeTailSkipSamples(sttManager?.getPrimaryProviderType())
        streamReadPosition = if (interruptedSpeech) {
            sharedBuffer?.getCurrentPosition() ?: (bufferPosition + tailSkip)
        } else {
            bufferPosition + tailSkip
        }
        wakeWordDetector?.stop()   // stop detection during STT recording
        cascadeHistory.clear()     // DLG-6: fresh wake = new context (a re-arm keeps the history)
        reArmedFollowUp = false    // fresh wake = full listening UI (a re-arm shows only the bottom bar)
        startSttSession()
    }

    /**
     * Mic ownership swap for `AndroidSpeechRecognizerProvider`, which captures its OWN audio and
     * would otherwise fight our shared capture. Here STT ending IS the turn ending, so `restore`
     * re-arms the wake word — the cascade row of [SharedCaptureMicHandoff]'s table (HA Voice
     * Assist takes the other, and the difference is the whole reason that class exists).
     */
    private val androidSttMicHandoff = SharedCaptureMicHandoff(
        capture = { audioCaptureService },
        wakeWord = { wakeWordDetector },
        handler = handler,
        reArmWakeWord = { isEnabled && !inConversation },
        tag = TAG,
        beforeRelease = { stopAudioStreaming() },
        canRestore = { isEnabled },
    )

    /** WS-D.1: the free TTS to speak with while degraded, or null to honor the user's setting. */
    fun degradedTtsProvider(): String? = degraded.ttsProvider

    /** CR2 "Not now" (pass expiresInMs = 24h) / "Don't show again" (expiresInMs = 0, no expiry):
     *  the TEMPORARY runtime fallback to free engines — the in-memory DegradedVoiceMode override
     *  that auto-restores when credits return, on restart, or (for "Not now") after [expiresInMs].
     *  Distinct from "Use local voice", which PERSISTS via LocalVoiceSwitch. False ⇒ no free
     *  engine exists on this device. */
    fun fallBackToLocalVoiceTemporarily(expiresInMs: Long): Boolean =
        degraded.enter("user deferred at \$0", expiresInMs)

    /**
     * Install the STT provider priority for the current credit state, BEFORE opening a session.
     * While degraded, the manager's whole priority chain is the FREE chain — so its recoverable-
     * error fallback (an offline lead → next provider) can only ever reach another free engine,
     * never the billed Deepgram. Otherwise restore the user's configured priority.
     *
     * This replaced a per-session `preferredType` lead: that left the manager's FALLBACK list as
     * the configured (billed) one, so an offline local Whisper fell back to Deepgram → 402 →
     * the out-of-credits chooser (found on-device 2026-07-20).
     */
    private fun applySttPriorityForCreditState() {
        if (degraded.isActive) {
            val chain = degraded.sttChain
            if (chain.isNotEmpty()) sttManager?.setProviderPriority(chain)
        } else if (configuredSttPriority.isNotEmpty()) {
            sttManager?.setProviderPriority(configuredSttPriority)
        }
    }

    /**
     * Start an STT session with the best available provider
     */
    private fun startSttSession() {
        turnGeneration++   // new turn — any in-flight prior turn's callbacks are now stale
        endOfSpeechHandled = false
        receivedTranscript = false   // FB19: reset per window (gates the VAD + no-transcript timeout)
        // Engage AEC3 (and its noise suppressor) for the whole turn, not just while Dashie
        // is speaking. The raw mic floors at 0.010–0.016 RMS on some devices — straddling
        // the VAD's 0.02 speech threshold — so a fresh-wake turn could flip-flop and never
        // endpoint. See CascadeAecController.onTurnStart.
        aecControllerProvider?.invoke()?.onTurnStart()
        setState(PipelineState.CONNECTING)

        val buffer = sharedBuffer
        if (buffer == null) {
            handleError("No audio buffer available")
            return
        }

        // Reset VAD
        vad?.reset()
        recordingStartTime = System.currentTimeMillis()

        applySttPriorityForCreditState()

        // Start STT session
        val success = sttManager?.startSession(object : SttListener {
            override fun onSessionStarted() {
                Log.d(TAG, "STT session started")
                setState(PipelineState.LISTENING)
                startAudioStreaming()
                armNoTranscriptTimeout()   // FB19: give up on a silent window at 6s, not the 10s cap
            }

            override fun onInterimResult(text: String) {
                Log.d(TAG, "Interim STT: $text")
                receivedTranscript = true   // FB19: real speech began — trust the VAD, drop the silent timeout
                cancelNoTranscriptTimeout()
            }

            override fun onFinalResult(text: String) {
                Log.i(TAG, "Final STT result: $text")
                receivedTranscript = true
                cancelNoTranscriptTimeout()
                stopAudioStreaming()
                reportSttStage()   // Wave 2: stt_ms + engine → the turn's timing row

                // Resume wake word immediately so user can interrupt
                // during PROCESSING or SPEAKING with a new "Hey Dashie"
                handler.post { wakeWordDetector?.start() }

                // Notify UI
                handler.post { onSttResult?.invoke(text) }

                // Process through overlay NLP
                processTranscriptWithOverlay(text)
            }

            override fun onNoSpeechDetected() {
                Log.d(TAG, "No speech detected")
                stopAudioStreaming()
                handleNoSpeech()
            }

            override fun onError(error: String, isRecoverable: Boolean) {
                Log.e(TAG, "STT error: $error (recoverable=$isRecoverable)")
                stopAudioStreaming()
                handleError(error)
            }

            override fun onSessionEnded() {
                Log.d(TAG, "STT session ended")
            }
        }) ?: false

        if (!success) {
            handleError("Failed to start STT session")
        }
    }

    /**
     * Start streaming audio to the STT provider
     */
    private fun startAudioStreaming() {
        val buffer = sharedBuffer ?: return

        // The seam's second consumer — see SttProvider.ownsMic for why this is a property and not
        // a per-call-site condition. HaLocalSttStage carries the same guard.
        if (sttManager?.activeOwnsMic == true) {
            Log.i(TAG, "Provider ${sttManager?.activeProviderId} owns the mic — no audio streaming")
            return
        }

        Log.d(TAG, "Starting audio streaming")

        streamingRunnable = object : Runnable {
            override fun run() {
                // Stop once end-of-speech has been handled (the VAD's onEndOfSpeech
                // can fire mid-iteration; without this the loop re-posts itself and
                // undoes stopAudioStreaming).
                if (currentState != PipelineState.LISTENING || endOfSpeechHandled) {
                    return
                }

                // Check max duration
                val elapsed = System.currentTimeMillis() - recordingStartTime
                if (elapsed > MAX_RECORDING_DURATION_SEC * 1000) {
                    Log.d(TAG, "Max recording duration reached")
                    handleEndOfSpeech()
                    return
                }

                // Read audio from buffer (100ms worth = 1600 samples at 16kHz)
                val samplesToRead = 1600
                val audioChunk = buffer.readFrom(streamReadPosition, samplesToRead)

                if (audioChunk.isNotEmpty()) {
                    streamReadPosition += audioChunk.size

                    // Convert to PCM bytes
                    val pcmBytes = floatToPcm16(audioChunk)

                    // Process through VAD (may fire onEndOfSpeech → handleEndOfSpeech)
                    vad?.processAudio(pcmBytes)
                    if (endOfSpeechHandled) return   // VAD ended the turn — don't stream more or re-post

                    // Stream to STT provider
                    sttManager?.streamAudio(pcmBytes)
                }

                // Schedule next chunk
                handler.postDelayed(this, 100)
            }
        }

        handler.post(streamingRunnable!!)
    }

    /**
     * Stop audio streaming
     */
    private fun stopAudioStreaming() {
        streamingRunnable?.let { handler.removeCallbacks(it) }
        streamingRunnable = null
    }

    /** VAD end-of-speech (FB19): the VAD trips on the wake-word tail / noise before the user
     *  speaks, so honor it only once a transcript arrived (silent turns end via the timeout). */
    private fun onVadEndOfSpeech() {
        if (!receivedTranscript) {
            Log.d(TAG, "VAD end-of-speech ignored — no transcript yet (tail/noise)")
            vad?.reset()
            return
        }
        handleEndOfSpeech()
    }

    /**
     * Handle end of speech detection
     */
    private fun handleEndOfSpeech() {
        if (currentState != PipelineState.LISTENING || endOfSpeechHandled) {
            return
        }
        endOfSpeechHandled = true

        Log.d(TAG, "End of speech detected")
        sttAudioEndAtMs = System.currentTimeMillis()   // Wave 2: stt_ms anchor
        cancelNoTranscriptTimeout()
        stopAudioStreaming()
        sttManager?.endAudioStream()
    }

    /** Wave 2: report stt_ms (audio-end → final transcript) + the engine that actually
     *  delivered, to the webapp's timing row (window.dashieVoiceTiming). Consumes the
     *  anchor so a stale one can't attach to a later turn. Best-effort analytics. */
    private fun reportSttStage() {
        val anchor = sttAudioEndAtMs; sttAudioEndAtMs = 0L
        // Prefer the provider's measured POST latency (buffered HA Whisper ends audio on its
        // internal VAD → onFinalResult beats the coordinator VAD, so `anchor` races to 0);
        // streaming providers return null → use the anchor. Resolve ha_engine → real engine id.
        val ms = sttManager?.lastTranscribeMs?.takeIf { it >= 0 }
            ?: (System.currentTimeMillis() - anchor).takeIf { anchor > 0L } ?: return
        val provider = sttManager?.activeProviderId
        val engine = if (provider == "ha_engine")
            halitePrefs.voice.haSttEngineId.takeIf { it.isNotBlank() } ?: provider else provider
        VoiceStageTiming.reportStt(ms, engine)
    }

    /** FB19: give up on a silent window at NO_TRANSCRIPT_TIMEOUT_MS (else only the 10s cap did).
     *  Normally disarmed before it fires — by an interim transcript (streaming STT) or by the
     *  VAD hearing speech (buffered STT). If it DOES fire with audio already captured, flush
     *  that audio to the engine rather than binning it: a discarded utterance is the worst
     *  outcome available, and "I didn't hear anything" after the user just spoke a full
     *  sentence is exactly the bug this used to cause. */
    private fun armNoTranscriptTimeout() {
        cancelNoTranscriptTimeout()
        noTranscriptTimeoutRunnable = Runnable {
            if (currentState == PipelineState.LISTENING && !receivedTranscript && !endOfSpeechHandled) {
                if (vad?.hasSpeech == true) {
                    Log.i(TAG, "No transcript within ${NO_TRANSCRIPT_TIMEOUT_MS}ms but speech WAS captured — flushing to STT")
                    handleEndOfSpeech()   // flush + transcribe (same path as the max-duration cap)
                    return@Runnable
                }
                Log.i(TAG, "No transcript within ${NO_TRANSCRIPT_TIMEOUT_MS}ms — ending silent turn")
                endOfSpeechHandled = true
                stopAudioStreaming()
                sttManager?.cancelSession()
                handleNoSpeech()
            }
        }
        handler.postDelayed(noTranscriptTimeoutRunnable!!, NO_TRANSCRIPT_TIMEOUT_MS)
    }

    private fun cancelNoTranscriptTimeout() {
        noTranscriptTimeoutRunnable?.let { handler.removeCallbacks(it) }
        noTranscriptTimeoutRunnable = null
    }

    /**
     * Process transcript through overlay NLP
     */
    private fun processTranscriptWithOverlay(transcript: String) {
        val gen = turnGeneration
        val announcement = scheduledAnnouncement; scheduledAnnouncement = false
        // A scheduled action's REMINDER CARD is its surface — the full-screen voice overlay
        // (dim backdrop + panel) is conversation UI and nobody asked a question here. Held for
        // the whole turn (cleared on IDLE) so the indicator can be suppressed while it runs.
        if (announcement) announcementTurnActive = true

        // Native app-launch fast-path ("open Netflix / YouTube TV / Spotify") — handled on
        // device before the cloud hand-off; non-app phrasings fall through to the brain.
        if (!announcement && AppLaunchIntercept.tryHandle(
                transcript,
                speak = onSpeakResponse,
                onSpeaking = { setState(PipelineState.SPEAKING) },
                onComplete = { handler.post { handleVoiceInteractionComplete() } },
            ) { q, pkg, label ->
                cancelSpeculativeConversationEngine()
                handler.post { com.dashieapp.Dashie.halite.apps.AppLaunchController.instance?.openApp(q, pkg, label) }
            }
        ) return

        // Native weather fulfillment (punch #4) — HA Assist can't do weather ("no weather
        // is exposed") and a $0-degraded turn has no cloud brain, so in those two modes a
        // weather question would die. Answer it on-device via WeatherVoiceTool (the same
        // executor the cloud brain / Live already use). Gated to aiModel=home_assistant or
        // degraded ONLY: in cloud/AI mode the brain owns weather (it calls WeatherVoiceTool
        // via the LLM, handling indirect phrasings the native regex won't).
        if (!announcement &&
            (halitePrefs.voice.aiModel ==
                com.dashieapp.Dashie.halite.preferences.VoicePreferences.AI_MODEL_HOME_ASSISTANT ||
                degraded.isActive)
        ) {
            val wxArgs = WeatherIntercept.classify(transcript)
            if (wxArgs != null) { answerWeatherNatively(wxArgs, gen); return }
        }

        // Conversation mode (ON-DEMAND): the user says the trigger phrase → enter Live.
        // In ALWAYS mode the trigger is unnecessary — the cascade classifies as usual
        // (HA/timers/sports stay local) and the JS defers any conversational
        // (no-local-match) query to Live via ai_lane below. (build plan §3.1)
        if (halitePrefs.voice.conversationModeEnabled && !halitePrefs.voice.conversationAlways
            && KotlinIntentPatterns.isConversationModeTrigger(transcript)) {
            Log.i(TAG, "🗣️ Conversation mode trigger — entering realtime session")
            startConversationSession()
            return
        }

        setState(PipelineState.PROCESSING)

        voiceOverlayBridge?.processVoiceCommandWithTts(transcript) { success, response ->
            // Ignore a response arriving after this turn was interrupted by a new wake word.
            if (gen != turnGeneration) {
                Log.d(TAG, "Ignoring stale overlay response (turn $gen ≠ $turnGeneration)")
                return@processVoiceCommandWithTts
            }
            // AI lane: a conversational (no-local-match) query deferred here by the JS
            // classifier → route to the engine for the mode. HA/timers/sports were handled
            // locally by the cascade and never reach this branch.
            if (success && response?.aiLane == true) {
                // HA FAST-PATH (§4): a device command for a known exposed entity runs on-device via HA
                // Assist and is presented like a LOCAL command — confirmation tone + native HA card, NO
                // conversation overlay — never entering a dialog/live session (matches full mode's 'ha'
                // toast). Off-main (WebView entity build + Assist HTTP). On a miss (not a device command,
                // Assist can't act, or an announcement) we fall through to the normal AI-lane dispatch.
                brainPreflight.execute {
                    val assist = if (announcement) null else tryHaFastPath(transcript)
                    handler.post {
                        if (gen != turnGeneration) return@post
                        if (assist != null && assist.actionDone) { presentHaCommand(transcript, assist); return@post }
                        // $0 degraded: a QUESTION must not hit the cloud brain (declines → chooser re-pop
                        // + full dialog). Answer via HA's conversation agent, else a plain decline.
                        if (!announcement && degraded.isActive) { answerDegradedQuestion(transcript, gen); return@post }
                        // Announcement: one cascade answer, no dialog loop, no re-arm.
                        when (if (announcement) "single" else effectiveAgentMode()) {
                            "live" -> {
                                Log.i(TAG, "🗣️ AI lane → Live (agentMode=live)")
                                beginAlwaysConversation(transcript)
                            }
                            "dialog" -> {
                                Log.i(TAG, "🗣️ AI lane → cascade Dialog (agentMode=dialog)")
                                cancelSpeculativeConversationEngine()
                                startCascadeDialog(transcript)
                            }
                            else -> {   // "single" — conversation, unlooped (build plan 20260720)
                                cancelSpeculativeConversationEngine()
                                if (announcement) {
                                    // Scheduled AI-turn: reminder-style card, no conversation UI.
                                    Log.i(TAG, "🗣️ AI lane → cascade single (announcement)")
                                    handleBrainConverse(transcript, announcement)
                                } else {
                                    // Single = a follow-up-only conversation session: same surface,
                                    // cards, miss policy, and barge-in as dialog; ends after the
                                    // first turn that stops asking. Stateless (#9).
                                    Log.i(TAG, "🗣️ AI lane → cascade single (conversation, unlooped)")
                                    startCascadeDialog(transcript, followupOnly = true)
                                }
                            }
                        }
                    }
                }
                return@processVoiceCommandWithTts
            }

            // Handled locally by the cascade (HA/timer/sports/answer) → drop the speculative Live session.
            cancelSpeculativeConversationEngine()

            // DLG-6: record this local command so a brain follow-up gets the earlier context.
            if (success) recordContextTurn(transcript, response?.voice?.takeIf { it.isNotBlank() })

            // Silent local command → IDLE, or DLG-6 re-arm (auto-wake: re-open the PRIMARY listen
            // after a beat so a confirmation tone isn't captured — the follow-up is a normal command).
            if (success && response?.silent == true) {
                // end_dialog: the command put something on SCREEN (a camera feed, a Frigate
                // clip). Keeping the dialog open would leave the voice overlay sitting on top
                // of the very thing the user asked to look at until the listen window times
                // out. Close now — regardless of the keep-dialog-open setting.
                if (response.endDialog) {
                    Log.i(TAG, "🚪 end_dialog: closing voice UI immediately (no re-arm)")
                    handler.post { handleVoiceInteractionComplete() }
                } else if (!announcement && shouldReArmAfterCommand(transcript)) {
                    Log.i(TAG, "🔁 Keep-dialog-open: silent command → re-arm")
                    handler.postDelayed({ reArmPrimaryListen() }, 600)
                } else handler.post { handleVoiceInteractionComplete() }
                return@processVoiceCommandWithTts
            }

            // Locally-intercepted MUSIC command → present like an HA command (full-mode toast
            // parity): compact card, NO speaking chrome, and the voice UI comes DOWN as the song
            // starts instead of sitting over the music player. The ack TTS already fired at the
            // bridge level. Must run BEFORE setState(SPEAKING) — that call itself draws the
            // standard response card, which is the "full voice overlay" a kiosk user reported.
            if (success && !announcement && response?.action?.optString("category") == "music") {
                presentLocalMusicAck(transcript, response)
                return@processVoiceCommandWithTts
            }

            // Full-mode AI answer (Phase 2, build plan 20260720): the JS lane already ran the
            // brain AND spoke — render on the SAME conversation surface kiosk/Live use, never the
            // legacy indicator. Unlooped, like native single (Phase 1). (Full-mode keep-dialog-open
            // re-arm is feature-flagged off and not wired here — the re-arm thread owns reviving it.)
            if (success && !announcement) {
                pendingReArm = false
                response?.let { presentBrainAnswerInConversation(transcript, it) }
                    ?: handleVoiceInteractionComplete()
                return@processVoiceCommandWithTts
            }

            pendingReArm = false
            if (success) {   // announcement — reminder-style card, not conversation UI
                setState(PipelineState.SPEAKING, response?.voice)
                com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showAnnouncementCard(response?.voice)
            } else {
                setState(PipelineState.SPEAKING, "Sorry, I couldn't process that")   // error/timeout — TTS via bridge
                // A FIRED action that failed used to draw nothing at all — it only spoke. If you
                // were out of the room or muted, a scheduled check that errored left zero trace:
                // you'd never know it ran, let alone failed. Leave a small corner card behind.
                if (announcement) {
                    com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showErrorCard(
                        "Couldn't run your scheduled check: “$transcript”"
                    )
                }
            }

            // 30s fallback → idle.
            handler.postDelayed({
                if (currentState == PipelineState.SPEAKING) handleVoiceInteractionComplete()
            }, 30_000)
        }
    }

    /**
     * AI lane: call the voice-conversation brain via the integration gateway (native), then speak
     * the result. The brain returns quickly for direct answers (~1.5s) and slower for web-search
     * two-pass (~15s). On failure, speak a graceful fallback. (v1: brain HA actions are not
     * dispatched — clear HA commands stay in the local lane.)
     */
    private fun handleBrainConverse(transcript: String, announcement: Boolean = false) {
        Log.i(TAG, "AI lane → brain converse: '$transcript'")
        runBrainTurn(transcript, dialog = false, announcement = announcement)
    }

    /** WS-D.1: answer a $0-degraded QUESTION as a plain spoken reply (HA conversation agent or a
     *  graceful decline) — never the cloud brain (declines → chooser re-pop) or the full dialog.
     *  Speaks via onSpeakResponse, which honors the free/degraded TTS override; then goes idle. */
    private fun answerDegradedQuestion(transcript: String, gen: Int) = brainPreflight.execute {
        val line = degradedQuestionHandler.answer(transcript, degraded.brainAllowed)
        handler.post {
            if (gen != turnGeneration) return@post
            // setState (not a bare field write) so the spoken answer also renders as the standard
            // response card — sibling of the native-weather display fix (2026-07-28).
            setState(PipelineState.SPEAKING, line)
            val done = { handler.post { if (gen == turnGeneration) handleVoiceInteractionComplete() } }
            onSpeakResponse?.invoke(line, null, null, null) { done() } ?: run { onTtsRequest?.invoke(line, false); done() }
        }
    }

    /** Punch #4: fulfill a weather question on-device via WeatherVoiceTool (Dashie's
     *  dashboard weather) rather than the brain/HA agent, which can't do weather. Gated to
     *  aiModel=home_assistant / degraded by the caller. Runs the snapshot read off-main and
     *  speaks via onSpeakResponse (which honors any free/degraded TTS override); then idle. */
    private fun answerWeatherNatively(args: org.json.JSONObject, gen: Int) = brainPreflight.execute {
        val out = try { WeatherVoiceTool.query(context, args) } catch (e: Exception) {
            Log.w(TAG, "🌤️ native weather failed: ${e.message}"); null
        }
        val line = (out?.optJSONObject("result")?.optString("voice").orEmpty())
            .ifBlank { "I couldn't get the weather right now." }
        handler.post {
            if (gen != turnGeneration) return@post
            // setState (not a bare field write) so onStateChanged draws the standard response
            // card — without it the native weather answer only SPOKE, invisible on screen
            // (device-confirmed 2026-07-28, Fire). Same surface as a local/brain single answer.
            setState(PipelineState.SPEAKING, line)
            val done = { handler.post { if (gen == turnGeneration) handleVoiceInteractionComplete() } }
            onSpeakResponse?.invoke(line, null, null, null) { done() } ?: run { onTtsRequest?.invoke(line, false); done() }
        }
    }

    /**
     * ONE brain turn for BOTH cascade modes — single IS dialog minus the loop
     * (unification 2026-07-09; the modes were parallel copies and drifted: single
     * dropped the brain's structured_data card, so sports/image cards never showed).
     * Shared: converse → credit gate → HA forward → BrainToolResolver (device tools)
     * → display + card (client_tool card, else the brain's structured_data) → speak
     * (+30s safety net). `dialog` differs ONLY at the marked seams: context threading
     * (conversationId + history), the brain dialog-policy (miss/endConversation —
     * single deliberately ignores it: one-shot turns speak whatever came), the display
     * surface (indicator vs conversation overlay), and completion (IDLE vs re-arm loop).
     */
    private fun runBrainTurn(transcript: String, dialog: Boolean, announcement: Boolean = false) {
        val gen = turnGeneration
        // Reset the progress label at every turn start — the brain only streams a status
        // when it routes to a tool, so a direct-answer follow-up otherwise shows the
        // PREVIOUS turn's label ("Checking the score…") for its whole thinking phase
        // (observed stuck across numerous follow-ups after a score ask, 2026-07-12).
        handler.post { onBrainProgress?.invoke("Thinking") }
        // Calendar-color (20260711, Phase 3): deterministic sniff + calendar-window
        // pre-fetch (the JS pre-flight, via the calendar bridge) so the brain answers
        // schedule questions directly in ONE pass — member-attributed digest, single AND
        // multi-event. Null (non-calendar turn / empty window / timeout) → converse
        // proceeds without it, and the client_tool template path answers as before.
        // OFF-MAIN by construction: the bridge blocks on evaluateJavascript round-trips.
        brainPreflight.execute {
            val calCtx = runCatching { calendarBridge.buildContext(transcript) }.getOrNull()
            converseBrain(transcript, dialog, gen, calCtx, announcement)
        }
    }

    /** The converse leg of [runBrainTurn] (split so the pre-flight executor hop doesn't
     *  re-indent the callback). `calCtx` = the pre-flight's `{provided, card}` or null;
     *  `card` is HELD here and rendered only when the brain confirms the direct path
     *  (metadata.calendar_context_used) — same window bounds data and display. */
    private fun converseBrain(transcript: String, dialog: Boolean, gen: Int, calCtx: JSONObject?, announcement: Boolean = false) {
        val heldCalendarCard = calCtx?.optJSONObject("card")
        // HA entities (20260717): the voice-controllable set the JS fast path already builds
        // (getVoiceEntityIds honoring the account's entitySource + entitiesForBrain + device area).
        // Attaching it as provided_context.ha_entities lets the LLM resolve + call HA on a turn the
        // fast path missed — without it, "turn on the string lights" reached the brain with nothing
        // to act on. OFF-MAIN by construction (runBrainTurn's pre-flight executor); null on any
        // failure/timeout → proceed without, exactly like the calendar pre-flight above.
        // The HA fast-path (device command → HA Assist, no brain) runs UPSTREAM in the AI-lane branch
        // of processTranscriptWithOverlay, before any dialog opens — see tryHaFastPath/presentHaCommand.
        // By here the turn is genuinely for the brain; ha_entities still rides along as context so the
        // LLM can resolve a device reference the fast-path's gate didn't claim.
        val haCtx = runCatching { haEntitiesBridge.build() }.getOrNull()
        // A logged-in device (account JWT cached natively, even in kiosk display mode)
        // calls the cloud brain DIRECTLY with its own account — not the integration's
        // HA-token gateway. Kiosk is a display choice; account-linking is separate.
        val conn = halitePrefs.connection
        val cloudJwt = if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) conn.supabaseJwt else null
        // Dialog seam: thread the brain-assigned conversation id + recent history (DLG-6).
        val priorHistory = if (dialog) {
            org.json.JSONArray().apply { cascadeHistory.takeLast(8).forEach { put(it) } }.takeIf { it.length() > 0 }
        } else null
        // Wave 2 timing anchors (submitNativeTurn at onDone): brain start, turn-window start.
        val brainStartMs = System.currentTimeMillis(); val turnStartMs = recordingStartTime
        val timingRoute = if (halitePrefs.voice.effectiveUseLocalBrain(halitePrefs.householdAnswersGovern)) "local" else "cloud"
        brainConverseClient.converse(
            transcript = transcript,
            // getHaOrigin() — clean scheme://host:port; haUrl carries the dashboard
            // suffix (e.g. /ha-dashie) which would break the /api/dashie path.
            haUrl = conn.getHaOrigin() ?: "",
            haToken = conn.haAccessToken,
            endpointId = endpointId,   // stable per-device id (see `endpointId` prop) — NOT Build.MODEL
            // On-prem add-on brain: the manual dev toggle (§13.17) OR the add-on-reported
            // route (Open Brain §5 — own model / Hermes / BYO key on the box).
            useLocalBrain = halitePrefs.voice.effectiveUseLocalBrain(halitePrefs.householdAnswersGovern),
            // Direct cloud brain when the account JWT is present (the logged-in-kiosk fix).
            cloudJwt = cloudJwt,
            conversationId = if (dialog) cascadeConversationId else null,
            history = priorHistory,
            // Pre-fetched calendar window → the brain digests it directly (old brains
            // ignore the key and route to the calendar tool as before).
            // Merge calendar (pre-flight) + HA entities/area into one provided_context. Each key is
            // attached only when present, so a non-calendar / non-HA turn sends a lean object (or
            // none). Shape matches callBrain's provided_context (brain-client.js).
            providedContext = JSONObject().apply {
                calCtx?.optJSONObject("provided")?.let { put("calendar", it) }
                haCtx?.optJSONArray("ha_entities")?.takeIf { it.length() > 0 }?.let { put("ha_entities", it) }
                haCtx?.optString("device_area")?.takeIf { it.isNotBlank() && it != "null" }?.let { put("device_area", it) }
            }.takeIf { it.length() > 0 },
            // Fire-time replay provenance. The device has always known this ("is this a
            // scheduled action firing, or a person talking?") but never told the brain —
            // so the brain offered schedule_action on a replayed turn and the device was
            // left guessing after the fact. Sending it lets the brain simply not offer the
            // tool, making a self-rescheduling (compounding) action impossible.
            announcement = announcement,
            // Live progress (thinking → tool status → finalizing) for BOTH modes; the
            // controller renders it on whichever surface the current mode owns. Stale
            // (superseded) turns drop their late stage events.
            onProgress = { status -> if (gen == turnGeneration) onBrainProgress?.invoke(status) },
        ) { result ->
            // CR2/CR4: refresh the cached credit state from the turn; a declined turn shows
            // the prompt-to-choose (via markExhausted → onExhausted) instead of speaking.
            CreditStateHolder.update(result?.creditSpendable, result?.creditLow, result?.creditBalance)
            if (result?.degraded == "insufficient_credits") {
                Log.i(TAG, "💳 Brain declined turn — insufficient credits")
                CreditStateHolder.markExhausted(result.creditBalance)
                handler.post {
                    if (dialog) { if (cascadeDialogActive) endCascadeDialog(idleClose = false) }
                    else if (gen == turnGeneration) handleVoiceInteractionComplete()
                }
                return@converse
            }
            // Dialog seam — unified dialog policy (20260707_DIALOG_POLICY_UNIFICATION): the brain
            // flags a no-intent turn (`miss`) + the give-up decision (`endConversation`). NEVER
            // speak a miss — speaking re-opens the mic and the TTS is re-heard as the next turn
            // (the false-trigger echo + token-burn loop). Silent notice; end or re-listen.
            if (dialog && (result?.miss == true || result?.endConversation == true)) {
                Log.i(TAG, "🗣️ Brain dialog-policy: miss=${result?.miss} end=${result?.endConversation} — silent, no speak")
                handler.post {
                    if (gen != turnGeneration || !cascadeDialogActive) return@post
                    // Consecutive-miss cap: a background TV feeds the follow-up mic transcribable
                    // noise turn after turn, each a miss. Count them; a real answer resets it below.
                    if (result?.miss == true) cascadeConsecutiveMisses++
                    // Miss-retry (Option C, John 2026-07-13): a FIRST-turn miss after an
                    // EXPLICIT wake gets ONE silent "Say that again?" re-listen — the user
                    // deliberately woke Dashie, so a silent give-up is almost always wrong
                    // (the "who vote? romeo and juliet" case). Never for re-armed follow-up
                    // windows (no fresh intent → noise ends quietly), never twice: a second
                    // miss falls through to the normal end-with-linger below.
                    if (result?.miss == true && cascadeTurns == 1 && cascadeExplicitWake && !cascadeMissRetried) {
                        Log.i(TAG, "🗣️ First-turn miss after explicit wake — one silent retry")
                        cascadeMissRetried = true
                        onConversationTranscript?.invoke(ConversationEngine.Speaker.DASHIE, "Say that again?", null, true)
                        cascadeReListen(freshWindow = true)
                        return@post
                    }
                    // Render the miss INSIDE the panel as a silent (never spoken — echo loop)
                    // Dashie turn: the FB28 notice no-ops in CONVERSATION mode, so a first-turn
                    // miss vanished the overlay with zero feedback (2026-07-13). idleClose=true
                    // leaves the panel up on the reading timer instead of a blink-out.
                    if (result?.miss == true) {
                        onConversationTranscript?.invoke(ConversationEngine.Speaker.DASHIE,
                            result.voice?.takeIf { it.isNotBlank() } ?: "Sorry, I didn't catch that.", null, true)
                    }
                    // End (don't re-listen) on an explicit end-intent, OR once misses pile up: the
                    // dialog is almost certainly hearing a TV, not a person. Ends after the Nth
                    // "didn't catch that" is shown, so the user still gets feedback, then quiet.
                    val missCapHit = result?.miss == true && cascadeConsecutiveMisses >= cascadeMaxConsecutiveMisses
                    if (missCapHit) Log.i(TAG, "🗣️ $cascadeConsecutiveMisses consecutive misses — ending dialog (TV/ambient?)")
                    if (result?.endConversation == true || missCapHit) endCascadeDialog(idleClose = true)
                    else cascadeReListen(freshWindow = false)
                }
                return@converse
            }
            // MULTI: a compound turn ("dim the light AND play that song"). Run every resolved leg
            // on-device now, then fall through to speak the ONE covering `voice` below
            // (BrainToolResolver.resolve returns BrainSpeak(result.voice) — multi's top-level
            // clientTool/action are null). Bg thread → safe to block. Capability-gated to devices
            // declaring `multi`. See MultiTurnDispatcher + JS↔Kotlin contract #33.
            var multiStartedMedia = false
            var multiSummary: MultiTurnDispatcher.Summary? = null
            if (result?.type == "multi" && (result.steps?.length() ?: 0) > 0) {
                Log.i(TAG, "🎛️ Brain→multi: ${result.steps!!.length()} step(s)")
                cascadeConsecutiveMisses = 0
                multiSummary = runCatching { MultiTurnDispatcher(haAssistConverse).run(result.steps) }
                    .onFailure { Log.w(TAG, "multi dispatch error", it) }.getOrNull()
                // A step that started a track closes the dialog below (mic must not hear it).
                multiStartedMedia = multiSummary?.startedMedia == true
                // fall through → brainToolResolver.resolve(result) → speaks result.voice once
                // (reconciled against the summary so a failed step isn't claimed as done).
            }
            // BRAIN → HA FALLBACK: the brain routed to Home Assistant — either an execute_commands
            // ACTION (when we sent ha_entities, the common case now) or unsupported_tool + commandHint
            // (when we didn't). v1 does NOT dispatch the brain's action, so it would just speak
            // "Turning off …" without doing it. Execute the command on-device via HA Assist — the SAME
            // native executor (HaAssistConverse) the fast-path uses — and present it like the fast-path
            // (tone + card). This is the safety net for a device command the on-device gate didn't
            // claim (unusual phrasing) but the brain understood. Prefer the brain's resolved commandHint
            // (handles referential "turn them off") over the raw transcript.
            val brainWantsHa = !announcement && (result?.unsupportedTool == "home_assistant" ||
                result?.action?.optString("category") == "homeassistant")
            if (brainWantsHa) {
                // PREFER the brain's RESOLVED execute_commands — it decomposes COMPOUND/multi-device
                // asks ("turn on X and dim Y") into separate service calls, which HA Assist's
                // single-intent conversation/process can't do (it returns error). Fall back to
                // forwarding the text to Assist only when there are no structured commands (an
                // unsupported_tool + commandHint, e.g. a referential "turn them off").
                val commands = result?.action?.optJSONObject("parameters")?.optJSONArray("commands")
                    ?: result?.action?.optJSONArray("commands")
                val brainVoice = result?.voice?.takeIf { it.isNotBlank() }
                val done = if (commands != null && commands.length() > 0) {
                    Log.i(TAG, "🏠 Brain→HA: dispatching ${commands.length()} resolved command(s)")
                    runCatching { haAssistConverse.executeCommands(commands) }.getOrNull()
                        ?.let { it.copy(actionDone = true, speech = brainVoice ?: it.speech) }
                } else {
                    val cmd = result?.commandHint?.takeIf { it.isNotBlank() } ?: transcript
                    Log.i(TAG, "🏠 Brain→HA: forwarding to Assist '$cmd'")
                    runCatching { haAssistConverse.process(cmd) }.getOrNull()?.takeIf { it.actionDone }
                }
                if (done != null && done.actionDone) {
                    handler.post {
                        if (gen != turnGeneration) return@post
                        cascadeConsecutiveMisses = 0
                        if (dialog) presentHaCommandInDialog(transcript, done, gen) else presentHaCommand(transcript, done)
                    }
                    return@converse
                }
                Log.i(TAG, "🏠 Brain→HA: couldn't execute — speaking the brain's line as a last resort")
                // fall through to brainToolResolver.resolve below
            }
            // AI-LANE FALLBACK (Phase 1 #1): local/BYO brain UNREACHABLE (result == null — timeout, NOT a
            // WS-I.8 structured BYO error) → route through HA Assist (sibling of a408599b); questions shrug below.
            if (!announcement && result == null && halitePrefs.voice.effectiveUseLocalBrain(halitePrefs.householdAnswersGovern) &&
                !conn.getHaOrigin().isNullOrBlank() && conn.haAccessToken.isNotBlank()) {
                Log.i(TAG, "🏠 Local/BYO brain unreachable — falling back to HA Assist: '$transcript'")
                val done = runCatching { haAssistConverse.process(transcript) }.getOrNull()
                    ?.takeIf { it.actionDone }
                if (done != null) {
                    handler.post {
                        if (gen != turnGeneration) return@post
                        cascadeConsecutiveMisses = 0
                        if (dialog) presentHaCommandInDialog(transcript, done, gen) else presentHaCommand(transcript, done)
                    }
                    return@converse
                }
                Log.i(TAG, "🏠 HA Assist couldn't handle it either — graceful shrug below")
            }
            // Personality switches apply NATIVELY (both Android modes — PersonalityActionApplier);
            // any other non-HA brain action forwards to the JS action registry (see bridge KDoc).
            result?.action?.let {
                if (!PersonalityActionApplier.tryApply(context, it)) voiceOverlayBridge?.executeVoiceAction(it)
            }
            // On OkHttp's background thread — safe to block on the on-device weather/calendar tools.
            val spoken = brainToolResolver.resolve(result)
            // Multi: the brain committed its confirmation before the steps ran — repair it against
            // what actually executed so a failed leg isn't spoken as done (over-claim gap).
            val voice = if (multiSummary != null && multiSummary.unfulfilled.isNotEmpty()) {
                Log.w(TAG, "🎛️ multi over-claim repair: ${multiSummary.executed} ran, " +
                    "unfulfilled=${multiSummary.unfulfilled.joinToString()}")
                MultiTurnDispatcher.reconcileVoice(spoken.voice, multiSummary) ?: spoken.voice
            } else spoken.voice
            // Console transcript: a device-fulfilled turn's spoken reply exists only HERE —
            // report it onto the ai_interactions row (fire-and-forget; consent-gated
            // server-side: only rows already carrying prompt_text accept the fill).
            if (result?.clientTool != null && !spoken.failed) {
                brainConverseClient.reportDeviceTurnResponse(
                    cloudJwt, result.conversationId ?: (if (dialog) cascadeConversationId else null), voice)
            }
            handler.post {
                // Drop a brain reply that lands after the turn was interrupted (the
                // "stale Sorry 20s later" case — common when a local-LLM turn hangs).
                if (gen != turnGeneration || (dialog && !cascadeDialogActive)) {
                    Log.d(TAG, "Ignoring stale brain response (turn $gen ≠ $turnGeneration)")
                    return@post
                }
                // Card source is the SAME in both modes: the device-fulfilled card
                // (client_tool: calendar) else the brain's structured_data (sports/image),
                // else the HELD pre-flight calendar card when the brain confirmed the
                // direct calendar path (calendar-color 20260711 — voice from the brain,
                // card from the same pre-fetched window). Only the RENDER SURFACE is a
                // display seam (below) — single draws into the voice indicator, dialog
                // into the conversation overlay.
                val card = spoken.card ?: result?.card
                    ?: heldCalendarCard?.takeIf { result?.calendarContextUsed == true }
                if (dialog) {
                    // A real answer breaks any background-noise miss streak.
                    cascadeConsecutiveMisses = 0
                    // Dialog seam: thread context + speak into the conversation overlay.
                    result?.conversationId?.let { cascadeConversationId = it }
                    cascadeHistory.add(JSONObject().put("role", "user").put("text", transcript))
                    cascadeHistory.add(JSONObject().put("role", "assistant").put("text", voice))
                    if (spoken.screenAnswer) {
                        // Card-only ack (schedule creation / camera feed): the bell card / feed IS
                        // the visual — tear the conversation panel down NOW and speak the one-liner
                        // over the dashboard, instead of holding the overlay open until TTS ends
                        // (2026-07-18: creation showed BOTH the overlay and the card). TTS
                        // fires below regardless — speak()/onTtsRequest is not panel-driven.
                        endCascadeDialog(idleClose = false)
                    } else {
                        onConversationTranscript?.invoke(ConversationEngine.Speaker.DASHIE, voice, result?.text?.takeIf { it.isNotBlank() && it != voice }, true)
                        awaitingCascadeTts = true
                        // §7-2b: a follow-up-only dialog (single-mode calendar-write promotion)
                        // closes after the first turn that stops asking — receipt/cancel spoken,
                        // then end at TTS end instead of re-listening.
                        if (cascadeFollowupOnly && !spoken.needsFollowup) cascadeEndAfterTts = true
                        // A MULTI turn is ALL COMMANDS — done once its one confirmation is spoken.
                        // Field 2026-07-18: re-listening after "turn off the lights and play the
                        // beatles" caught the music it had just started. Honors DLG-6 for chaining.
                        if (result?.type == "multi" && !keepOpenAfterCommand(transcript)) cascadeEndAfterTts = true
                        // Music is PLAYING out of the speaker — end regardless of any pref: "keep
                        // dialog open" chains SILENT commands, and no setting makes re-listening
                        // into the track we just started correct.
                        if (spoken.startedMedia || multiStartedMedia) cascadeEndAfterTts = true
                        // A FAILED turn ENDS the dialog — it must never re-arm. `failed` means we
                        // never got a real answer and `voice` is the generic apology, so re-listening
                        // leaves the mic open on a "sorry" panel with nothing coming. During ANY
                        // backend fault every turn ends this way, so a persistent outage otherwise
                        // leaves every kiosk in the house permanently armed — a privacy surface, not
                        // just an annoyance (field 2026-08-19: John's tablet sat listening 12+ min on
                        // an error panel and had to be dismissed by hand). idleClose=true keeps the
                        // apology up for its reading window, then IDLE.
                        if (spoken.failed) {
                            Log.w(TAG, "DROP: dialog turn failed — ending conversation instead of re-arming")
                            cascadeEndAfterTts = true
                        }
                        currentState = PipelineState.SPEAKING   // direct field — no cascade chrome over the overlay
                        // Overlay renders cards type-dispatched (sports/image/calendar).
                        card?.let { onConversationCard?.invoke(it) }
                    }
                } else if (spoken.needsFollowup && !announcement) {
                    // §7-2b (single mode): the calendar-write tool asked a question
                    // (which calendar / confirm) — a one-shot turn can't hear the answer,
                    // and the answer needs THIS question in the brain's history. Promote
                    // into the dialog loop, marked follow-up-only so it ends the moment a
                    // turn stops asking. Mirrors startCascadeDialog minus the first-turn
                    // dispatch (this turn already ran).
                    Log.i(TAG, "📅 calendar-write follow-up in single mode — promoting to follow-up-only dialog")
                    cascadeFollowupOnly = true
                    cascadeDialogActive = true
                    inConversation = true
                    cascadeTurns = 1
                    cascadeExplicitWake = false   // no miss-retry credit for promoted turns
                    cascadeMissRetried = false
                    cascadeConsecutiveEmpty = 0
                    armCascadeAbsolute()   // promotion bypasses startCascadeDialog — arm the bound here too
                    result?.conversationId?.let { cascadeConversationId = it }
                    recordContextTurn(transcript, voice)
                    sttManager?.setEndpointingMs(CASCADE_ENDPOINTING_MS)
                    onConversationStart?.invoke()   // open the overlay (bottom bar)
                    onConversationTranscript?.invoke(ConversationEngine.Speaker.USER, transcript, null, true)
                    onConversationTranscript?.invoke(ConversationEngine.Speaker.DASHIE, voice, null, true)
                    awaitingCascadeTts = true       // TTS end → cascadeReListen hears the answer
                    currentState = PipelineState.SPEAKING
                    card?.let { onConversationCard?.invoke(it) }
                } else {
                    // Announcement seam: since Phase 1 routed interactive single mode through the
                    // followupOnly dialog, dialog=false reaches here ONLY for a scheduled AI-turn
                    // (announcement=true). Its reminder-style card IS the surface — never the
                    // conversation overlay (nobody asked a question). A failed check leaves an
                    // error card behind so a muted/absent user still sees it ran.
                    if (spoken.failed) {
                        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showErrorCard(
                            "Couldn't run your scheduled check: “$transcript”")
                        setState(PipelineState.IDLE)
                    } else {
                        setState(PipelineState.SPEAKING, voice)
                        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showAnnouncementCard(voice)
                    }
                }
                // Wave 2: emit the whole voice_turn_timing row at playback end (onDone) — the
                // native brain turn skips the JS router, so STT+TTS stages sit in VoiceStageTiming.
                val brainMs = System.currentTimeMillis() - brainStartMs
                val tSid = if (dialog) cascadeConversationId else result?.conversationId
                val tDone = java.util.concurrent.atomic.AtomicBoolean(false)
                // Speak via the account cloud voice (native-owned). Completion seam: dialog
                // re-arms the mic at playback end (onCascadeTtsEnded); single goes IDLE.
                // Fall back to onTtsRequest on older wiring; 30s safety net either way.
                val onDone: () -> Unit = {
                    // Idempotent: real TTS completion AND the 30s net (below) both call this; only
                    // the first may act. Old code guarded just the timing submit, so the never-
                    // cancelled net re-armed the mic MID-SPEECH ~2 turns later (device-confirmed
                    // 2026-07-17). The stale net carries its own consumed tDone → guarding the whole
                    // body makes it a no-op while the live turn's real onDone still acts.
                    if (tDone.compareAndSet(false, true)) {
                        VoiceStageTiming.submitNativeTurn(
                            tSid, turnStartMs, brainMs,
                            // followupOnly sessions ARE single mode riding the dialog plumbing —
                            // keep the cascade_single label so mode telemetry stays comparable.
                            if (dialog && !cascadeFollowupOnly) "cascade_dialog" else "cascade_single",
                            null, timingRoute, true)
                        // Route by LIVE dialog state, not the closure's `dialog`: a single-mode
                        // turn that promoted itself into the follow-up-only dialog (§7-2b,
                        // calendar-write question) must re-arm via onCascadeTtsEnded, not IDLE out.
                        if (dialog || cascadeDialogActive) { if (cascadeDialogActive && awaitingCascadeTts) onCascadeTtsEnded() }
                        else if (currentState == PipelineState.SPEAKING) handleVoiceInteractionComplete()
                    }
                }
                val speak = onSpeakResponse
                if (speak != null) {
                    val convId = if (dialog) cascadeConversationId else result?.conversationId
                    speak(voice, result?.voiceId, result?.voiceProvider, convId) { onDone() }
                } else {
                    onTtsRequest?.invoke(voice, false)
                }
                handler.postDelayed({ onDone() }, 30_000)
            }
        }
    }

    // ── Cascade Dialog (agentMode='dialog'): Engine B as a VPC loop ──────────
    // One continuous conversation using VPC's OWN STT + brain (BrainConverseClient) + TTS, driving
    // the native conversation overlay. Each response's TTS end re-arms STT (8s idle close). §EngineB(C).
    private var cascadeDialogActive = false
    private var awaitingCascadeTts = false
    private var pendingReArm = false   // DLG-6: a spoken command awaits its confirmation TTS end to re-arm the primary listen
    private var reArmedFollowUp = false // DLG-6: the current listen is a silent-follow-up re-arm → suppress the no-speech notice
    private var cascadeTurns = 0
    private val cascadeMaxTurns = 12
    // Miss-retry (Option C): one silent "Say that again?" per dialog, only when the
    // dialog began from an EXPLICIT wake word (not a DLG-6 re-armed follow-up listen).
    private var cascadeExplicitWake = false
    private var cascadeMissRetried = false
    // §7-2b (calendar write in single mode): the dialog was opened ONLY to hear the
    // answer(s) to a device-tool question (slot-fill / confirm). It ends as soon as a
    // turn stops asking (needsFollowup=false) — single stays one-shot in spirit.
    private var cascadeFollowupOnly = false
    // Set when the current turn's TTS should be the dialog's last — onCascadeTtsEnded
    // ends the dialog instead of re-listening.
    private var cascadeEndAfterTts = false
    // Absolute end (now+8s) of the follow-up window; empty end-of-speech re-listens while time remains.
    private var cascadeWindowDeadlineMs = 0L
    // One STT-error retry per follow-up window — a transient Deepgram drop shouldn't end the dialog.
    private var cascadeRetriedThisWindow = false
    // Consecutive brain misses in this dialog. A TV in the background feeds the follow-up mic a
    // stream of transcribable-but-meaningless speech; each returns miss=true → "didn't catch that"
    // → re-listen, an unbounded "Sorry, I didn't catch that." loop (2026-07-17). Cap it: after
    // N misses in a row, end the dialog instead of re-listening. A real answer resets the streak.
    private var cascadeConsecutiveMisses = 0
    /** Consecutive listen windows that produced NO usable turn (empty final, or an STT error we
     *  retried). Distinct from [cascadeConsecutiveMisses], which counts turns the BRAIN answered
     *  as noise — these never reach the brain, so that cap can't see them. This is the one
     *  backstop nothing cancels: the 8s idle timer and the window deadline are both cleared by
     *  any interim result, so speech-shaped ambient noise re-armed forever (field 2026-08-19). */
    private var cascadeConsecutiveEmpty = 0
    private val cascadeMaxConsecutiveEmpty = 3
    private val cascadeMaxConsecutiveMisses = 2
    // Wake-prefix strip + end-intent vocabulary → CascadeDialogSupport (shared, stateless).
    private val cascadeIdleTimer = Runnable {
        Log.i(TAG, "🗣️ cascade Dialog idle — closing")
        endCascadeDialog(idleClose = true)
    }
    /** ABSOLUTE dialog bound — the one stop room noise cannot delete. [onSpeechStarted] is the
     *  ENERGY VAD (RMS, not recognition), so a door or cough kills both other stops for the rest of
     *  the dialog; the shape John's tablet hung in was then a DEAD STT session emitting NO callback
     *  under a live window — which no callback counter (incl. [cascadeConsecutiveEmpty]) can see and
     *  a wall clock can. Re-armed ONLY by a real transcript: noise must never refresh
     *  it, or it inherits the same defeat as the timers it backs up.
     *  45s is DERIVED, not picked — re-derive if any constant changes. It must clear the worst
     *  LEGITIMATE no-transcript stretch in LISTENING, which the in-flight extension does NOT cover
     *  (STT finalizes while still LISTENING): idle 8s + MAX_RECORDING 10s + endpointing 1s +
     *  HaVoiceService.CONNECT_TIMEOUT 15s ≈ 34s → 45s ≈ 1.3×. Deliberately NOT the 8s window: that
     *  fires constantly in healthy operation, this must never — at 8s it would cut a legit 10s
     *  utterance in half. */
    private val cascadeAbsoluteMs = 45_000L
    /** Extensions for a turn genuinely in flight — CAPPED, so the bound stays absolute: a turn
     *  wedged in PROCESSING would otherwise renew forever, re-creating the hang one state over. */
    private val cascadeMaxAbsoluteExtensions = 3
    private var cascadeAbsoluteExtensions = 0
    private val cascadeAbsoluteTimer = Runnable { onCascadeAbsoluteBound() }
    private fun armCascadeAbsolute() {
        cascadeAbsoluteExtensions = 0
        handler.removeCallbacks(cascadeAbsoluteTimer)
        handler.postDelayed(cascadeAbsoluteTimer, cascadeAbsoluteMs)
    }
    private fun cancelCascadeAbsolute() = handler.removeCallbacks(cascadeAbsoluteTimer)
    private fun onCascadeAbsoluteBound() {
        if (!cascadeDialogActive) return
        // A turn in flight is not a hang (long question + brain + long answer can outlive the
        // bound before the next transcript exists); cutting there would sever a real conversation
        // — the regression T re-checks. Extend instead, a bounded number of times.
        if ((currentState == PipelineState.PROCESSING || currentState == PipelineState.SPEAKING) &&
            cascadeAbsoluteExtensions < cascadeMaxAbsoluteExtensions) {
            cascadeAbsoluteExtensions++
            Log.i(TAG, "🗣️ absolute dialog bound reached but a turn is in flight ($currentState) — " +
                "extension $cascadeAbsoluteExtensions/$cascadeMaxAbsoluteExtensions")
            handler.postDelayed(cascadeAbsoluteTimer, cascadeAbsoluteMs)   // NOT armCascadeAbsolute: must not reset the count
            return
        }
        Log.w(TAG, "DROP: absolute dialog bound — ${cascadeAbsoluteMs}ms with no transcript " +
            "(state=$currentState, extensions=$cascadeAbsoluteExtensions), closing dialog")
        endCascadeDialog(idleClose = true)
    }
    // Multi-turn brain context: brain-assigned id + prior [{role,text}] turns (last 8 sent).
    private var cascadeConversationId: String? = null
    // DLG-6: doubles as the keep-alive CONTEXT history — the brain gets it on a follow-up so
    // "turn them back on" resolves against the earlier "turn the string lights on". Populated for
    // LOCAL commands too (they never reach the brain); cleared on a fresh wake, kept across re-arms.
    private val cascadeHistory = mutableListOf<JSONObject>()

    /** DLG-6: record a turn into the keep-alive context. Capped so it stays a recent window. */
    private fun recordContextTurn(user: String, assistant: String?) {
        cascadeHistory.add(JSONObject().put("role", "user").put("text", user))
        assistant?.let { cascadeHistory.add(JSONObject().put("role", "assistant").put("text", it)) }
        while (cascadeHistory.size > 16) cascadeHistory.removeAt(0)
    }

    /** [followupOnly] = the SINGLE-mode unification (build plan 20260720): the session is
     *  "conversation, unlooped" — same surface, same miss policy, same barge-in, but it ends
     *  after the first turn that stops asking (the 1192 needsFollowup check) and a device
     *  command ends it regardless of the DLG-6 keep-open pref. Stateless by design: a fresh
     *  session starts with no conversationId (decision #9, John 2026-07-20). */
    private fun startCascadeDialog(initialText: String, followupOnly: Boolean = false) {
        if (cascadeDialogActive) return
        cascadeDialogActive = true
        cascadeFollowupOnly = followupOnly
        inConversation = true
        cascadeTurns = 0
        cascadeConversationId = null
        // reArmedFollowUp still reflects the listen that produced initialText: false =
        // a fresh "Hey Dashie" (explicit intent → a first-turn miss earns one retry).
        cascadeExplicitWake = !reArmedFollowUp
        cascadeMissRetried = false
        cascadeConsecutiveMisses = 0
        cascadeConsecutiveEmpty = 0
        armCascadeAbsolute()
        // NOTE: do NOT clear cascadeHistory here — a DLG-6 follow-up that routes to the brain must
        // keep the earlier local-command context. It's cleared on a fresh wake (onWakeWordDetected).
        sttManager?.setEndpointingMs(CASCADE_ENDPOINTING_MS)  // patient: mid-thought pauses in a back-and-forth
        // Keep the wake word detector RUNNING through the dialog — it IS the barge-in mechanism
        // (onWakeWordDetected's cascadeDialogActive branch); shared capture stays up so the pull
        // detector has audio (precedent: single mode runs it during TTS). Don't stop it.
        handler.post { onConversationStart?.invoke() }    // open the overlay (bottom bar)
        cascadeDialogTurn(initialText)
    }

    private fun cascadeDialogTurn(transcript: String) {
        if (!cascadeDialogActive) return
        cancelCascadeIdle()
        val t = CascadeDialogSupport.stripWakePrefix(transcript)
        handler.post { onConversationTranscript?.invoke(ConversationEngine.Speaker.USER, t, null, true) }
        if (t.isEmpty()) { endCascadeDialog(idleClose = true); return }
        cascadeConsecutiveEmpty = 0   // a real transcript breaks the unproductive-window streak
        armCascadeAbsolute()          // …and is the ONLY thing that refreshes the absolute bound
        // End-of-conversation ("thanks"/"never mind"/"shut up"…) is decided by the brain
        // (dialog-policy.ts → metadata.end_conversation, honored below as result.endConversation),
        // NOT a native shortcut — see contract #5. The turn goes to the brain, which ends silently.
        if (++cascadeTurns > cascadeMaxTurns) { endCascadeDialog(idleClose = false); return }
        // Track the REAL pipeline state via direct field writes (not setState — no cascade chrome
        // over the conversation overlay); getState() consumers + the barge-in branch rely on it.
        currentState = PipelineState.PROCESSING
        handler.post { onConversationState?.invoke(ConversationEngine.State.SPEAKING) }   // thinking → dots
        // The turn itself is the SHARED runner (single = dialog minus the loop; see runBrainTurn).
        runBrainTurn(t, dialog = true)
    }

    /** DLG-6 keep-open: execute a brain-resolved HA command WITHOUT leaving the dialog. Runs it via
     *  the local overlay bridge (forward_to_assist executes on the HA side), renders the confirmation
     *  as a Dashie turn, speaks it, then continues the cascade loop (onCascadeTtsEnded → cascadeReListen)
     *  so the panel stays open. A silent device command (no spoken confirmation) just re-listens.
     *  processVoiceCommand (not …WithTts) so the bridge doesn't ALSO fire onTtsRequest — we own the TTS. */
    private fun executeHaCommandInDialog(userText: String, command: String, gen: Int) {
        val bridge = voiceOverlayBridge ?: run {
            // No bridge (shouldn't happen in kiosk) → legacy tear-down fallback so the command still runs.
            if (cascadeDialogActive) endCascadeDialog(idleClose = false)
            processTranscriptWithOverlay(command); return
        }
        // forceHandler: the brain ALREADY routed this as home_assistant — without it the JS
        // router re-classified question-phrased queries ("is the garage door open") back to
        // the AI lane and the loop guard silently ate the answer (2026-07-13).
        bridge.processVoiceCommand(command, object : VoiceOverlayBridge.VoiceResponseCallback {
            override fun onResponse(response: VoiceOverlayBridge.VoiceResponse) {
                handler.post {
                    if (gen != turnGeneration || !cascadeDialogActive) return@post
                    // Thread this exchange into history so the next follow-up keeps context.
                    val reply = response.voice?.takeIf { it.isNotBlank() }
                    cascadeHistory.add(JSONObject().put("role", "user").put("text", userText))
                    cascadeHistory.add(JSONObject().put("role", "assistant").put("text", reply ?: "Done"))
                    // Silent HA command (the common case — the native HA confirmation card, shown
                    // via DashieNative.showHaCommandResult from the JS handler, IS the confirmation),
                    // an empty reply, or one the JS re-deferred to the AI lane (loop guard): no spoken
                    // Dashie turn → keep the dialog open and re-listen. DELAY the re-listen so the
                    // confirmation tone finishes first — an immediate cascadeReListen armed the mic
                    // during the beep and the follow-up wasn't heard (the AI path re-listens only
                    // after TTS ends, which is why THAT worked). Mirrors the old reArmPrimaryListen delay.
                    if (reply == null || response.silent || response.aiLane) {
                        // …but only when the user opted into follow-up-after-commands. With "keep
                        // dialog open" OFF a device command ends the turn (see presentHaCommandInDialog).
                        // No structured HA result on this lane (JS bridge executes) — the transcript
                        // device-noun heuristic vetoes media-starting commands ("turn on the TV").
                        if (!keepOpenAfterCommand(userText,
                                startedMedia = ReArmPolicy.haCommandStartsMedia(userText))) {
                            Log.i(TAG, "🏠 in-dialog command done — keep-dialog-open OFF, ending")
                            endCascadeDialog(idleClose = true)
                            return@post
                        }
                        Log.i(TAG, "🏠 in-dialog command done (silent=${response.silent}) — re-listen in ${CASCADE_POSTCOMMAND_RELISTEN_MS}ms")
                        handler.postDelayed({
                            if (cascadeDialogActive && gen == turnGeneration) cascadeReListen(freshWindow = true)
                        }, CASCADE_POSTCOMMAND_RELISTEN_MS)
                        return@post
                    }
                    onConversationTranscript?.invoke(ConversationEngine.Speaker.DASHIE, reply, null, true)
                    awaitingCascadeTts = true
                    // Single-shot session: a spoken command confirmation is not an ASK — end at
                    // TTS end instead of re-listening (followupOnly, build plan 20260720).
                    if (cascadeFollowupOnly) cascadeEndAfterTts = true
                    currentState = PipelineState.SPEAKING
                    // Idempotent completion, same fix as the main turn path: real TTS-end + 3s/30s
                    // nets all funnel here; the fire-time awaitingCascadeTts guard isn't enough (it's
                    // true again next turn), so a stale net re-arms mid-speech without a once-latch.
                    val dlgDone = java.util.concurrent.atomic.AtomicBoolean(false)
                    val onDlgDone: () -> Unit = {
                        if (dlgDone.compareAndSet(false, true)) {
                            if (cascadeDialogActive && awaitingCascadeTts) onCascadeTtsEnded()
                        }
                    }
                    val speak = onSpeakResponse
                    if (speak != null) {
                        speak(reply, null, null, cascadeConversationId) { onDlgDone() }
                    } else {
                        onTtsRequest?.invoke(reply, false)
                        handler.postDelayed({ onDlgDone() }, 3_000)
                    }
                    handler.postDelayed({ onDlgDone() }, 30_000)
                }
            }
            override fun onError(error: String) {
                handler.post { if (gen == turnGeneration && cascadeDialogActive) cascadeReListen(freshWindow = true) }
            }
            override fun onTimeout() {
                handler.post { if (gen == turnGeneration && cascadeDialogActive) cascadeReListen(freshWindow = true) }
            }
        }, forceHandler = "homeassistant")
    }

    /** HA fast-path decision+execute (OFF-main; called from the AI-lane branch). Returns HA Assist's
     *  result when this is a device command for a KNOWN exposed entity that Assist actioned, else null
     *  (→ the caller dispatches the turn to the brain/dialog). A cheap control-verb pre-filter skips
     *  the WebView entity build on non-command turns (weather, chitchat) so only likely device commands
     *  pay the round-trip. Assist re-validates, so the gate can be generous — a miss just falls back. */
    private fun tryHaFastPath(transcript: String): com.dashieapp.Dashie.voice.realtime.HaAssistConverse.Result? {
        if (!HaEntityMatcher.hasControlVerb(transcript)) return null
        // Scheduling qualifier ("in 10 minutes", "at 9:30", "tonight") → defer to the brain so its
        // schedule_action tool arms the alarm; claiming it here would make Assist run the command NOW.
        // Mirrors the JS HA classifier's guard (homeassistant-intents.js) — full mode's webapp path.
        if (HaEntityMatcher.hasSchedulingQualifier(transcript)) {
            Log.i(TAG, "⏰ HA fast-path deferring to brain — scheduling qualifier in '$transcript'")
            return null
        }
        // Compound spanning tools ("turn off the lights AND play jazz") → defer to the brain so it
        // emits a {type:'multi'} turn (MultiTurnDispatcher fans it out). Fast-pathing here runs only
        // the HA half and drops the rest — the on-device gap found 2026-07-18. Contract #34.
        if (HaEntityMatcher.hasSecondToolVerb(transcript)) {
            Log.i(TAG, "🎛️ HA fast-path deferring to brain — compound multi-tool in '$transcript'")
            return null
        }
        val ctx = runCatching { haEntitiesBridge.build() }.getOrNull() ?: return null
        val ents = ctx.optJSONArray("ha_entities") ?: return null
        if (ents.length() == 0 ||
            !HaEntityMatcher.shouldRouteToAssist(transcript, ents, ctx.optString("device_area"))) return null
        return runCatching { haAssistConverse.process(transcript) }.getOrNull()
    }

    /** Full-mode (or any JS-lane) brain ANSWER → the conversation surface (Phase 2, build plan
     *  20260720). Display-only on the SAME VoiceConversationView kiosk/Live use — chip + reply +
     *  the ONE card renderer (conversationCard type-dispatch: sports/slate/image/calendar). The JS
     *  lane already ran the brain and spoke; unlooped, dwell-then-idle (see below). */
    private fun presentBrainAnswerInConversation(userText: String, response: com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.VoiceResponse) {
        Log.i(TAG, "🗣️ Full-mode brain answer → conversation surface (card=${response.cardJson?.optString("type") ?: "none"})")
        val gen = turnGeneration
        val reply = response.voice ?: ""
        inConversation = true
        handler.post {
            onConversationStart?.invoke()
            onConversationTranscript?.invoke(ConversationEngine.Speaker.USER, userText, null, true)
            val extra = response.text?.takeIf { it.isNotBlank() && it != reply }
            onConversationTranscript?.invoke(ConversationEngine.Speaker.DASHIE, reply, extra, true)
            response.cardJson?.let { onConversationCard?.invoke(it) }
        }
        currentState = PipelineState.SPEAKING   // direct field — no cascade chrome over the panel
        // Local answer (time/date) in cloud mode: JS didn't voice it + native TTS skipped → speak it, else silent (2026-07-21).
        if (!response.localHandler.isNullOrBlank() && reply.isNotBlank() && halitePrefs.voice.voicePipelineMode == VoicePreferences.VOICE_PIPELINE_MODE_AI)
            onSpeakResponse?.invoke(reply, null, null, null) {} ?: onTtsRequest?.invoke(reply, false)
        // JS owns TTS (no native TTS-end): HOLD the panel a reading window, THEN end. Ending now
        // tears down before the panel paints (showXxx posts next loop). gen-guarded vs a new wake.
        handler.postDelayed({
            if (inConversation && gen == turnGeneration) resumeAfterConversation(idleClose = false)
        }, VoiceIndicatorConstants.readingBufferMs(reply.length))
    }

    /** Present a locally-intercepted music command like full mode's small toast: compact card, no
     *  speaking chrome, voice UI down as the song starts (2026-07-18 — the overlay was sitting
     *  over the music player). The ack TTS already fired at the bridge; the card + the music player
     *  appearing are the visual confirmation. DLG-6 re-arm still honored. */
    private fun presentLocalMusicAck(userText: String, response: com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.VoiceResponse) {
        Log.i(TAG, "🎵 Local music command — compact ack, dropping voice overlay")
        cancelSpeculativeConversationEngine()
        val message = response.voice?.takeIf { it.isNotBlank() } ?: "OK"
        recordContextTurn(userText, message)
        handler.post { onLocalMusicResult?.invoke(message, userText) }
        // A command that STARTED a track must not re-arm — 600ms later the mic would open onto the
        // song itself. Transport/read commands (pause, volume, what's playing) leave the room silent,
        // so they still honor "keep dialog open" and chain normally. Field 2026-07-18.
        val startedPlayback = com.dashieapp.Dashie.halite.music.MusicVoiceTool.startsAudiblePlayback(
            response.action?.optString("command"))
        if (shouldReArmAfterCommand(userText, startedMedia = startedPlayback)) {
            Log.i(TAG, "🔁 Keep-dialog-open: music command → re-arm")
            handler.postDelayed({ reArmPrimaryListen() }, 600)
        } else {
            if (startedPlayback) Log.i(TAG, "🎵 Playback started — not re-arming (mic would hear the track)")
            handler.post { handleVoiceInteractionComplete() }
        }
    }

    /** Present a fast-path HA result like full mode's 'ha' toast: confirmation tone + native HA card,
     *  NO conversation overlay. Then silent-complete (or DLG-6 re-arm if keep-dialog-open) — an HA
     *  command is a device action, not a conversation, so it never opens the panel. Runs on main. */
    private fun presentHaCommand(userText: String, assist: com.dashieapp.Dashie.voice.realtime.HaAssistConverse.Result) {
        Log.i(TAG, "🏠 HA fast-path (Assist) handled '$userText' — brain skipped")
        cancelSpeculativeConversationEngine()
        val message = assist.speech?.takeIf { it.isNotBlank() } ?: "Done"
        // Confirmation tone (respects the user's Confirmation Sound setting) — same as the JS 'ha' path.
        if (halitePrefs.voice.confirmationToneEnabled) {
            runCatching {
                com.dashieapp.Dashie.halite.audio.ConfirmationTonePlayer.play(
                    context, halitePrefs.voice.confirmationToneType, halitePrefs.voice.confirmationToneVolume)
            }
        }
        recordContextTurn(userText, message)   // DLG-6: a later brain follow-up gets this context
        onHaCommandResult?.invoke(message, userText)   // native HA confirmation card — NOT the overlay
        // Silent local command: DLG-6 re-arm if keep-dialog-open, else finish. The tone/card IS the
        // confirmation (no TTS), matching full mode. Delay the re-arm so the tone isn't recaptured.
        // A command that started room audio ("turn on the TV") never re-arms — same veto as music.
        val startedMedia = ReArmPolicy.haCommandStartsMedia(userText, assist.mediaTargeted)
        if (shouldReArmAfterCommand(userText, startedMedia = startedMedia)) {
            Log.i(TAG, "🔁 Keep-dialog-open: HA command → re-arm")
            handler.postDelayed({ reArmPrimaryListen() }, 600)
        } else {
            if (startedMedia) Log.i(TAG, "📺 HA command started media — not re-arming (mic would hear it)")
            handleVoiceInteractionComplete()
        }
    }

    /** Same as [presentHaCommand] (tone + native card, no TTS) but for a command the brain routed to
     *  HA while a cascade dialog is OPEN — keep the loop alive: re-listen instead of completing. */
    private fun presentHaCommandInDialog(userText: String, assist: com.dashieapp.Dashie.voice.realtime.HaAssistConverse.Result, gen: Int) {
        Log.i(TAG, "🏠 Brain→HA executed via Assist (in-dialog) '$userText' — brain action skipped")
        val message = assist.speech?.takeIf { it.isNotBlank() } ?: "Done"
        if (halitePrefs.voice.confirmationToneEnabled) {
            runCatching {
                com.dashieapp.Dashie.halite.audio.ConfirmationTonePlayer.play(
                    context, halitePrefs.voice.confirmationToneType, halitePrefs.voice.confirmationToneVolume)
            }
        }
        cascadeHistory.add(JSONObject().put("role", "user").put("text", userText))
        cascadeHistory.add(JSONObject().put("role", "assistant").put("text", message))
        onHaCommandResult?.invoke(message, userText)
        // An HA command is a device ACTION, not a conversation turn: with "keep dialog open" OFF it
        // must not hold the mic. Until now the dialog lane re-listened unconditionally, so the pref
        // had NO effect inside a dialog. (Gate on keepOpenAfterCommand — see its doc for why
        // shouldReArmAfterCommand can't be used here.) startedMedia veto: "turn on the TV" must not
        // re-listen into the TV audio it just started, no matter the pref.
        val startedMedia = ReArmPolicy.haCommandStartsMedia(userText, assist.mediaTargeted)
        if (startedMedia) Log.i(TAG, "📺 in-dialog HA command started media — ending instead of re-listening")
        if (cascadeDialogActive && keepOpenAfterCommand(userText, startedMedia = startedMedia)) {
            handler.postDelayed({
                if (cascadeDialogActive && gen == turnGeneration) cascadeReListen(freshWindow = true)
            }, CASCADE_POSTCOMMAND_RELISTEN_MS)
        } else if (cascadeDialogActive) {
            Log.i(TAG, "🏠 in-dialog HA command — keep-dialog-open OFF, ending instead of re-listening")
            endCascadeDialog(idleClose = true)
        } else handleVoiceInteractionComplete()
    }

    /** TTS finished → re-arm: a dialog turn (cascadeReListen) or a DLG-6 command (reArmPrimaryListen). */
    fun onCascadeTtsEnded() {
        if (cascadeDialogActive && cascadeEndAfterTts) {
            // §7-2b: the follow-up-only dialog just spoke its closing line (receipt or
            // cancel ack) — end with the panel lingering, don't re-open the mic.
            cascadeEndAfterTts = false
            awaitingCascadeTts = false
            endCascadeDialog(idleClose = true)
            return
        }
        if (cascadeDialogActive && awaitingCascadeTts) { awaitingCascadeTts = false; cascadeReListen(); return }
        if (pendingReArm) { pendingReArm = false; reArmPrimaryListen() }
    }

    // Re-arm policy lives in ReArmPolicy (pure, unit-tested) — read its KDoc before touching either
    // lane; the two lanes disagree inside a dialog ON PURPOSE. These wrappers only bind live state.

    /** DLG-6 "keep dialog open" as the policy should SEE it. Hidden behind a build flag since
     *  2026-07-18 — accounts still carry a stored `true` (the console seeded it for cloud/hybrid),
     *  so this must gate the READ, not just the settings UI, or those users would keep chaining
     *  with no toggle to stop it. Stored values are left intact. See [VoiceFeatureFlags]. */
    private val keepDialogOpenPref: Boolean
        get() = VoiceFeatureFlags.KEEP_DIALOG_OPEN_ENABLED && halitePrefs.voice.alwaysOpenDialog

    /** Dialog lane: keep the loop alive after an in-dialog COMMAND turn? (Q/A turns always re-listen.)
     *  A followupOnly (single-mode) session NEVER chains commands — the DLG-6 keep-open pref is a
     *  dialog feature; single-shot ends after a device command no matter the stored pref. */
    private fun keepOpenAfterCommand(transcript: String, startedMedia: Boolean = false): Boolean =
        !cascadeFollowupOnly && ReArmPolicy.shouldReListenAfterDialogCommand(
            alwaysOpenDialog = keepDialogOpenPref, transcript = transcript, startedMedia = startedMedia)

    /** DLG-6 (non-dialog) lane — structurally false inside a dialog, NOT a policy signal there. */
    private fun shouldReArmAfterCommand(transcript: String, startedMedia: Boolean = false): Boolean =
        ReArmPolicy.shouldReArmNonDialog(
            enabled = isEnabled, micMuted = micMuted, dialogActive = cascadeDialogActive,
            // effectiveAgentMode, not the raw pref: on an anon kiosk conversationEngineMode is the
            // account-pushed value that never arrived — the probed/derived mode is what the session
            // actually runs, and a kiosk whose effective mode is "single" must never chain.
            singleMode = effectiveAgentMode() == "single",
            alwaysOpenDialog = keepDialogOpenPref,
            transcript = transcript, startedMedia = startedMedia)

    /** DLG-6: after a re-armable command, re-open the PRIMARY listen (startSttSession) — auto-wake.
     *  The follow-up is a normal command (normal UI, local fast-path) that re-arms again on completion;
     *  NOT the dialog loop. startSttSession's no-speech/error paths close to IDLE + wake word (can't stick). */
    private fun reArmPrimaryListen() {
        if (!isEnabled || micMuted || cascadeDialogActive ||
            currentState == PipelineState.LISTENING || currentState == PipelineState.CONNECTING) {
            handleVoiceInteractionComplete(); return
        }
        Log.i(TAG, "🔁 Keep-dialog-open: re-arming primary listen")
        wakeWordDetector?.stop()
        streamReadPosition = sharedBuffer?.getCurrentPosition() ?: 0L   // live head (no wake buffer pos)
        reArmedFollowUp = true   // BEFORE startSttSession so CONNECTING+LISTENING both show the minimal UI
        startSttSession()
    }

    /** DLG-6: is the current listen a re-armed follow-up? (UI shows only the bottom bar, not the full overlay.) */
    fun isReArmedFollowUp(): Boolean = reArmedFollowUp

    /** True while a SCHEDULED action's turn is running — its card is the surface, so the voice
     *  overlay/backdrop must stay down (HaliteVoiceController's onStateChanged checks this). */
    @Volatile
    private var announcementTurnActive = false
    fun isAnnouncementTurn(): Boolean = announcementTurnActive

    private fun cascadeReListen(freshWindow: Boolean = true) {
        if (!cascadeDialogActive) return
        // freshWindow → new window: (re)set the 8s deadline; a re-listen keeps the original one.
        if (freshWindow) {
            cascadeWindowDeadlineMs = System.currentTimeMillis() + RealtimeConfig.DEFAULT_IDLE_MS
            cascadeRetriedThisWindow = false
        }
        // Re-arm STT (no wake word) mirroring the wake-word start: bump turnGeneration (stale
        // callbacks bail — FB24: a superseded session's late fallback-error killed the live
        // dialog on-device 2026-07-05), clear end-of-speech + reset VAD + recording clock, point
        // the reader at the LIVE buffer head, set LISTENING via direct field (guard passes, no 2nd bar).
        turnGeneration++
        val gen = turnGeneration
        endOfSpeechHandled = false
        vad?.reset()
        recordingStartTime = System.currentTimeMillis()
        streamReadPosition = sharedBuffer?.getCurrentPosition() ?: 0L
        currentState = PipelineState.LISTENING
        handler.post { onConversationState?.invoke(ConversationEngine.State.LISTENING) }   // orange line (conversation overlay)
        applySttPriorityForCreditState()
        val started = sttManager?.startSession(object : SttListener {
            override fun onSessionStarted() { if (gen == turnGeneration) startAudioStreaming() }
            override fun onInterimResult(text: String) {
                if (gen != turnGeneration) return
                // Speech underway → drop the 8s window (VAD/max-duration ends the turn now, so a
                // long/late follow-up isn't cut off).
                cancelCascadeIdle()
                cascadeWindowDeadlineMs = 0L
            }
            override fun onFinalResult(text: String) {
                handler.post {
                    if (gen != turnGeneration) return@post
                    stopAudioStreaming()
                    // Wave 2: stage report only — the dialog loop doesn't write a JS
                    // timing row yet; whole-row logging (dashieVoiceTiming.logNativeTurn)
                    // lands with the CascadeDialogController extraction.
                    reportSttStage()
                    cascadeDialogTurn(text)
                }
            }
            override fun onNoSpeechDetected() {
                handler.post {
                    // Silent here reads exactly like "the callback never fired" — the shape the
                    // 12-minute hang presented as. Name it.
                    if (gen != turnGeneration || !cascadeDialogActive) {
                        Log.w(TAG, "DROP: no-speech callback ignored — " +
                            "gen=$gen/$turnGeneration dialogActive=$cascadeDialogActive")
                        return@post
                    }
                    stopAudioStreaming()
                    // Empty end-of-speech: keep listening while the 8s window remains. Deadline 0 =
                    // interim speech already arrived (empty/garbled final) → fresh window (FB24 pt2).
                    val msLeft = cascadeWindowDeadlineMs - System.currentTimeMillis()
                    if (cascadeWindowDeadlineMs == 0L) {
                        // ⚠️ THIS is the unbounded branch, and the cap belongs here and ONLY here.
                        // Getting to deadline==0 means onInterimResult fired, which ALSO called
                        // cancelCascadeIdle() — so both stops are already gone and a fresh window
                        // re-arms with nothing left to end it. Speech-shaped ambient noise
                        // (a TV) loops here forever: field 2026-08-19, 12+ min armed, panel up.
                        // Deliberately NOT counted on the `msLeft > 0` branch below — that one is
                        // still bounded by a live deadline that will fire, and counting it would
                        // cut a legitimate silent follow-up short of its 8s.
                        if (++cascadeConsecutiveEmpty >= cascadeMaxConsecutiveEmpty) {
                            Log.w(TAG, "DROP: $cascadeConsecutiveEmpty consecutive unbounded empty listens — ending dialog")
                            endCascadeDialog(idleClose = true)
                            return@post
                        }
                        Log.i(TAG, "🗣️ cascade re-listen: empty final after interim speech — fresh window")
                        cascadeReListen(freshWindow = true)
                    } else if (msLeft > 0L) {
                        Log.i(TAG, "🗣️ cascade re-listen: empty turn, ${msLeft}ms left in window — keep listening")
                        cascadeReListen(freshWindow = false)
                    } else {
                        endCascadeDialog(idleClose = true)
                    }
                }
            }
            override fun onError(error: String, isRecoverable: Boolean) {
                handler.post {
                    if (gen != turnGeneration || !cascadeDialogActive) return@post
                    stopAudioStreaming()
                    // Credit reject is terminal, never retried — end the dialog + State 3.
                    if (error.contains("insufficient_credits")) {
                        Log.i(TAG, "💳 STT rejected mid-dialog — insufficient credits")
                        CreditStateHolder.markExhausted()
                        onVoiceUnavailable?.invoke()
                        endCascadeDialog(idleClose = false)
                        return@post
                    }
                    // Same session cap on the error lane — NOT redundant with cascadeRetriedThisWindow:
                    // that flag is per-window and the retry below re-listens with freshWindow=true,
                    // which CLEARS it, so a repeating recoverable error retries without bound.
                    if (isRecoverable && ++cascadeConsecutiveEmpty >= cascadeMaxConsecutiveEmpty) {
                        Log.w(TAG, "DROP: $cascadeConsecutiveEmpty consecutive failed listens ($error) — ending dialog")
                        endCascadeDialog(idleClose = true)
                        return@post
                    }
                    // A transient STT drop shouldn't end the conversation — retry once, DELAYED so a
                    // 502/cold-start clears and this attempt's stragglers drain (instant retry raced).
                    if (isRecoverable && !cascadeRetriedThisWindow) {
                        cascadeRetriedThisWindow = true
                        Log.w(TAG, "🗣️ cascade re-listen: STT error ($error) — delayed retry in ${CASCADE_RETRY_DELAY_MS}ms")
                        handler.postDelayed({
                            if (gen != turnGeneration || !cascadeDialogActive) return@postDelayed
                            cascadeReListen(freshWindow = true)   // fresh window — the failure ate the old one
                        }, CASCADE_RETRY_DELAY_MS)
                    } else {
                        endCascadeDialog(idleClose = false)
                    }
                }
            }
            override fun onSessionEnded() {}
        }) ?: false
        if (!started) { endCascadeDialog(idleClose = false); return }
        // Arm the 8s idle backstop only for a fresh window (a re-listen keeps the original timer).
        if (freshWindow) armCascadeIdle()
    }

    private fun armCascadeIdle() {
        cancelCascadeIdle()
        handler.postDelayed(cascadeIdleTimer, RealtimeConfig.DEFAULT_IDLE_MS)   // 8s, matches Live
    }
    private fun cancelCascadeIdle() = handler.removeCallbacks(cascadeIdleTimer)

    private fun endCascadeDialog(idleClose: Boolean) {
        if (!cascadeDialogActive) return
        cascadeDialogActive = false
        awaitingCascadeTts = false
        cascadeFollowupOnly = false
        cascadeEndAfterTts = false
        cascadeWindowDeadlineMs = 0L
        sttManager?.setEndpointingMs(DEFAULT_ENDPOINTING_MS)  // back to snappy for commands/initial inquiries
        cancelCascadeIdle()
        cancelCascadeAbsolute()
        try { stopAudioStreaming() } catch (_: Exception) {}
        try { sttManager?.cancelSession() } catch (_: Exception) {}
        handler.post { onConversationEnd?.invoke(idleClose) }   // overlay teardown (leave-up on idle)
        resumeAfterConversation(idleClose = idleClose)
        // The conversation is over → release anything parked behind it. resumeAfterConversation's
        // setState(IDLE) usually drains, but setState only fires on a TRANSITION — if we were
        // already IDLE the parked task would sit there forever. Belt and braces.
        handler.post { idleGate.drain() }
    }

    // ── Conversation mode (on-demand realtime S2S) ───────────────────────
    // Agent-mode + relay-credential resolution live in VoiceSessionAccess — the one
    // place that knows the logged-in vs anonymous-kiosk split (Live-on-kiosk 2026-07-09).

    private fun effectiveAgentMode(): String = VoiceSessionAccess.effectiveAgentMode(halitePrefs)

    private fun resolveLiveBearer(onResult: (String?) -> Unit) = VoiceSessionAccess
        .resolveLiveBearer(halitePrefs, webView, { sttCredentialProvider?.currentBearer() }, onResult)

    private val liveTokenClient by lazy { com.dashieapp.Dashie.voice.realtime.LiveTokenClient() }

    /**
     * BYOK-for-Live: ALWAYS try to mint a Live-only ephemeral token from the box. If the
     * household has a Gemini key there, Live runs on it and skips the credit debit; if not,
     * the endpoint answers 503 `no_gemini_key`, [LiveTokenClient] returns null, and the caller
     * uses the Dashie-key path. (Build plan 20260723_BYOK_LIVE_EPHEMERAL_TOKENS.md.)
     *
     * ⚠️ **No toggle.** This used to be gated on `voice.liveByok`, a flag with **no UI writer
     * anywhere** — the console toggle it referenced was never built, so the feature was
     * unreachable except by editing the DB by hand. John, 2026-08-01: *"It should just see
     * there's a Gemini key and use it instead of credits."* The box already answers that
     * question authoritatively, so asking a stored boolean first could only ever be wrong.
     *
     * Best-effort by design: a failed mint must NEVER block the session, only downgrade it to
     * Dashie's key. Minted per session open (each new open re-mints past the ~2-min start window).
     */
    private fun resolveLiveToken(model: String, onResult: (String?) -> Unit) {
        val origin = halitePrefs.connection.getHaOrigin()
        val token = halitePrefs.connection.haAccessToken
        liveTokenClient.fetch(origin, token, model) { result -> onResult(result?.token) }
    }

    /**
     * Enter realtime conversation mode: hand the mic from the wake-word/STT pipeline
     * to the [GeminiLiveEngine] (own AudioRecord on VOICE_COMMUNICATION; the shared MIC
     * capture is fully released so the two don't contend on constrained devices),
     * resolve the relay credential (account JWT / kiosk session token), then start.
     * [initialText] (a pre-captured command) is sent as the first turn on connect so a
     * command spoken before the channel opened isn't lost; null → user just starts talking.
     */
    private fun startConversationSession(initialText: String? = null) {
        inConversation = true
        // Drive the dedicated conversation UI (transcript + listening line/dots),
        // NOT the cascade thinking indicator — set the field directly so getState()
        // stays sane without firing onStateChanged.
        currentState = PipelineState.PROCESSING
        handler.post { onConversationStart?.invoke() }

        // Hand off the mic.
        stopAudioStreaming()
        sttManager?.cancelSession()
        wakeWordDetector?.stop()
        audioCaptureService?.stop()

        val model = halitePrefs.voice.conversationModel.ifBlank { RealtimeConfig.DEFAULT_MODEL }

        // Resolve the relay credential (account JWT / kiosk session token), then the optional
        // BYOK Live token (always attempted; null when the box has no Gemini key → Dashie-key
        // path), then start.
        resolveLiveBearer { bearer ->
            resolveLiveToken(model) { liveToken ->
                handler.post {
                    val cfg = bearer?.let { RealtimeConfig.fromBearer(context, it, model = model) }
                        ?.copy(initialText = initialText?.takeIf { it.isNotBlank() }, liveToken = liveToken)
                    if (cfg == null) {
                        Log.w(TAG, "Conversation mode: no relay credential — aborting")
                        // Wording covers both cases: sign in (logged-out full device) or the
                        // kiosk session token not yet minted (sharing off / integration down).
                        onTtsRequest?.invoke("Conversation mode isn't available right now.", false)
                        resumeAfterConversation()
                        return@post
                    }
                    startConversationEngine(cfg)
                }
            }
        }
    }

    /**
     * Speculatively open the Live socket at wake (in parallel with STT) so it's warm
     * by classify time. deferMic = no mic / no UI until committed. Best-effort: if the
     * JWT isn't ready we just skip, and beginAlwaysConversation falls back to a fresh
     * (sequential) open.
     */
    private fun openSpeculativeConversationEngine() {
        if (speculativeOpen) return
        val model = halitePrefs.voice.conversationModel.ifBlank { RealtimeConfig.DEFAULT_MODEL }
        resolveLiveBearer { bearer ->
            resolveLiveToken(model) { liveToken ->
                handler.post {
                    val cfg = bearer?.let { RealtimeConfig.fromBearer(context, it, model = model) }
                        ?.copy(liveToken = liveToken)
                    if (cfg == null) { Log.w(TAG, "Speculative Live open: no credential — will fall back"); return@post }
                    Log.i(TAG, "Speculative Live connect (parallel with STT)")
                    startConversationEngine(cfg, deferMic = true)
                    speculativeOpen = true
                }
            }
        }
    }

    /** Commit the warm speculative session with the captured command, or — if none is
     *  ready — open a fresh one (sequential). */
    private fun beginAlwaysConversation(initialText: String) {
        val engine = conversationEngine
        if (speculativeOpen && engine != null && engine.isActive) {
            speculativeOpen = false
            inConversation = true
            currentState = PipelineState.PROCESSING
            handler.post { onConversationStart?.invoke() }
            // STT done — hand the mic to the engine's own AudioRecord.
            stopAudioStreaming()
            sttManager?.cancelSession()
            wakeWordDetector?.stop()
            audioCaptureService?.stop()
            // The engine re-checks liveness at commit. If the relay died since the isActive
            // read above (e.g. Google 1011 quota), it says so instead of swallowing the
            // utterance — recover by opening a fresh session rather than leaving dead air.
            if (!engine.beginWithText(initialText)) {
                Log.w(TAG, "DROP: warm Live session was dead at hand-off — opening a fresh one")
                inConversation = false
                startConversationSession(initialText)
            }
        } else {
            speculativeOpen = false
            startConversationSession(initialText)   // no warm session — open now
        }
    }

    /** Drop a speculative session we won't use (the query was a local command). The
     *  engine emits CLOSED → resumeAfterConversation no-ops (inConversation is false). */
    private fun cancelSpeculativeConversationEngine() {
        if (!speculativeOpen) return
        speculativeOpen = false
        Log.i(TAG, "Local command — dropping speculative Live connection")
        conversationEngine?.stop()
    }

    private fun startConversationEngine(cfg: RealtimeConfig, deferMic: Boolean = false) {
        // Pass the WebView provider so the engine's get_calendar_events tool can reach
        // the on-device JS calendar tool (window.dashieCalendarTool) — device-fulfilled.
        val engine = conversationEngine
            ?: GeminiLiveEngine(context, webViewProvider).also { conversationEngine = it }
        Log.i(TAG, "Conversation engine starting (model=${cfg.model}, session=${cfg.sessionId}, deferMic=$deferMic)")
        engine.start(cfg, object : ConversationEngine.Listener {
            override fun onStateChanged(state: ConversationEngine.State) {
                onConversationState?.invoke(state)
                when (state) {
                    ConversationEngine.State.CLOSED,
                    ConversationEngine.State.ERROR ->
                        resumeAfterConversation(idleClose = engine.closedByIdle)
                    else -> { /* listening/thinking indicator driven via onConversationState */ }
                }
            }
            override fun onTranscript(speaker: ConversationEngine.Speaker, text: String, isFinal: Boolean) {
                onConversationTranscript?.invoke(speaker, text, null, isFinal)   // Live: no separate written text
            }
            override fun onCard(card: org.json.JSONObject) { onConversationCard?.invoke(card) }
            override fun onError(message: String) { Log.w(TAG, "Conversation engine error: $message") }
            override fun onLog(line: String) { Log.d(TAG, "[rt] $line") }
        }, deferMic)
    }

    /**
     * Leave conversation mode: restart the shared MIC capture + wake-word
     * detection and return to IDLE. Idempotent — the engine emits CLOSED on both
     * a normal end and a manual stop, and this only runs once per session.
     */
    private fun resumeAfterConversation(idleClose: Boolean = false) {
        if (!inConversation) return
        inConversation = false
        Log.i(TAG, "Conversation mode ended — resuming (idleClose=$idleClose)")
        handler.post { onConversationEnd?.invoke(idleClose) }
        if (!isEnabled) { setState(PipelineState.IDLE); return }
        audioCaptureService?.start()   // idempotent — no-ops if already running
        setState(PipelineState.IDLE)
        handler.postDelayed({ if (!inConversation && isEnabled) wakeWordDetector?.start() }, 400)
    }

    /** No speech (FB19): patience is up-front (no-transcript timeout, no re-listen); show a
     *  SUBTLE notice via onNoSpeechNotice — not the red ERROR dot, not the stale SPEAKING card. */
    private fun handleNoSpeech() {
        cancelNoTranscriptTimeout()
        // DLG-6: a silent follow-up just ends quietly (no "I didn't hear anything") + ends the
        // keep-alive session, so drop its context so it can't leak into the next fresh interaction.
        val notify = !reArmedFollowUp
        if (reArmedFollowUp) cascadeHistory.clear()
        if (notify) handler.post { onNoSpeechNotice?.invoke() }
        handler.postDelayed({
            setState(PipelineState.IDLE)
            if (isEnabled) wakeWordDetector?.start()
        }, if (notify) 2000 else 0)
    }

    /**
     * Handle error during voice interaction
     */
    private fun handleError(error: String) {
        Log.e(TAG, "Voice pipeline error: $error")

        // CR4 State 3: STT declined on credits — clean out-of-credits UX (toast + CR2 via
        // markExhausted, which also flips the wake cache so the NEXT wake skips STT), NOT the
        // red ERROR. Reject is non-recoverable, so no HA fallback.
        if (error.contains("insufficient_credits")) {
            CreditStateHolder.markExhausted()
            stopAudioStreaming()
            sttManager?.cancelSession()
            handler.post { onVoiceUnavailable?.invoke() }
            setState(PipelineState.IDLE)
            handler.postDelayed({ if (isEnabled) wakeWordDetector?.start() }, 100)
            return
        }

        stopAudioStreaming()
        sttManager?.cancelSession()

        setState(PipelineState.ERROR, error)
        onError?.invoke(error)

        // Return to idle after delay
        handler.postDelayed({
            setState(PipelineState.IDLE)
            wakeWordDetector?.start()
        }, 2000)
    }

    /**
     * Handle voice interaction complete.
     * Called internally after processing, or externally when TTS speech ends.
     */
    fun handleVoiceInteractionComplete() {
        Log.d(TAG, "Voice interaction complete")

        setState(PipelineState.IDLE)

        // Resume wake word detection
        handler.postDelayed({
            wakeWordDetector?.start()
        }, 500)
    }

    /**
     * Stop any ongoing voice interaction
     */
    fun stopVoiceInteraction() {
        Log.d(TAG, "Stopping voice interaction")

        // Capture BEFORE teardown clears the flags — decides whether a dedicated resume
        // path (endCascadeDialog / resumeAfterConversation) will restart the wake word,
        // or whether the plain-path restart below must.
        val wasDialog = cascadeDialogActive
        val wasConversation = inConversation

        // A cascade Dialog has no ConversationEngine, so the inConversation branch below can't
        // close it — without this, cancel (X) left it stuck (wake word dead). endCascadeDialog
        // also resumes the wake word.
        if (cascadeDialogActive) endCascadeDialog(idleClose = false)

        stopAudioStreaming()
        sttManager?.cancelSession()
        voiceOverlayBridge?.cancelAllPendingCommands()
        if (inConversation) conversationEngine?.stop()   // CLOSED → resumeAfterConversation()

        setState(PipelineState.IDLE)

        // A plain cascade/single cancel (X, no dialog/conversation) has no teardown path that
        // resumes the wake-word detector — endCascadeDialog / resumeAfterConversation cover only
        // their modes — so the X button left voice dead until app restart. Restart it here.
        if (!wasDialog && !wasConversation && isEnabled) {
            handler.post { wakeWordDetector?.start() }
        }
    }

    /**
     * Set voice state
     */
    private fun setState(newState: PipelineState, subtitle: String? = null) {
        if (currentState != newState) {
            Log.d(TAG, "State: $currentState -> $newState" + (subtitle?.let { " ($it)" } ?: ""))
            currentState = newState
            // Turn over → release the AEC capture window (it keeps a reverb tail of its own),
            // so the always-on wake-word path goes back to reading the RAW stream it was
            // tuned on. IDLE is the one funnel every ending passes through — completion,
            // cancel, no-speech timeout.
            if (newState == PipelineState.IDLE) aecControllerProvider?.invoke()?.onTurnEnd()
            handler.post {
                onStateChanged?.invoke(newState, subtitle)
                if (newState == PipelineState.IDLE) {
                    announcementTurnActive = false   // the scheduled turn is over
                    idleGate.drain()                 // release anything parked behind the conversation
                }
            }
        }
    }

    // Parks a scheduled action's presentation behind a live conversation — see ConversationIdleGate.
    // Busy = mid-turn, or a dialog is open (mic still armed for a follow-up).
    private val idleGate = ConversationIdleGate(handler) {
        cascadeDialogActive || currentState != PipelineState.IDLE
    }

    /** Run [block] now if the user isn't talking to Dashie, else when the conversation ends. */
    fun runWhenIdle(tag: String, block: () -> Unit) = idleGate.runWhenIdle(tag, block)

    /**
     * Get current state
     */
    fun getState(): PipelineState = currentState

    /** True while a cascade Dialog conversation owns the loop (controller consults this to
     *  keep music ducked + skip the post-TTS idle timer across dialog turns). */
    fun isDialogActive(): Boolean = cascadeDialogActive

    /** 🧪 Headless test entry (Dashie API voice injection, `@pipeline` prefix): run [text]
     *  through the SAME path a spoken turn takes — the classifier + AI-lane branch
     *  (single/dialog/live), or the open dialog's follow-up loop — so the whole pipeline
     *  is drivable over adb/HTTP with no mic. Password-gated by the API server. */
    fun injectTranscript(text: String, announcement: Boolean = false) {
        Log.i(TAG, "🧪 injectTranscript: '$text' (dialogActive=$cascadeDialogActive, announcement=$announcement)")
        handler.post {
            scheduledAnnouncement = announcement
            // A scheduled announcement takes the single-shot overlay path even if a
            // dialog is somehow active, so it never joins the conversation loop.
            if (cascadeDialogActive && !announcement) cascadeDialogTurn(text)
            else processTranscriptWithOverlay(text)
        }
    }

    /**
     * Get the VoiceOverlayBridge for wiring to DashieJSBridge.
     * Returns null if not initialized yet.
     */
    fun getOverlayBridge(): VoiceOverlayBridge? = voiceOverlayBridge

    /**
     * The LIVE provider set, for the STT benchmark ([SttBenchRunner]) only.
     *
     * Deliberately hands back the registered manager rather than letting the
     * bench build its own providers: these already carry the real, current
     * credentials (the HA token rotates ~30 min, Deepgram mints per session), so
     * a parallel set would either duplicate that resolution or bench against
     * dead tokens. Null until the pipeline has initialized.
     */
    fun sttManagerForBench(): SttProviderManager? = sttManager

    /**
     * Update HA token (e.g., after refresh)
     */
    fun updateHaToken(token: String) {
        (sttManager?.getProvider(SttProviderManager.ProviderType.HA_ASSIST) as? HaAssistSttProvider)
            ?.updateToken(token)
    }

    /**
     * Release resources
     */
    fun release() {
        stopVoiceInteraction()
        conversationEngine?.stop()
        conversationEngine = null
        sttManager?.release()
        sttManager = null
        voiceOverlayBridge?.release()
        voiceOverlayBridge = null
        vad = null
    }

    /**
     * Convert float audio samples to 16-bit PCM bytes
     */
    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val sample = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
        return bytes
    }
}

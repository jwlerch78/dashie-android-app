package com.dashieapp.Dashie.halite.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.ScreenDimmer
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import com.dashieapp.Dashie.wakeword.LiveSampleCollector
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseDetector
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordConfig
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordDetector
import com.dashieapp.Dashie.wakeword.models.WakeWordEngine
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.dashieapp.Dashie.halite.voice.lease.LeaseGovernance
import com.dashieapp.Dashie.halite.voice.lease.LeaseMarkers
import com.dashieapp.Dashie.halite.voice.lease.LeaseStateHolder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controller for Halite voice functionality.
 *
 * Manages:
 * - License/trial recovery at startup
 * - Microphone permission handling
 * - Wake word detection initialization
 * - HA voice service management
 * - Local TTS for voice responses
 * - Sample collection for training data
 *
 * Extracted from MainActivity as part of the refactoring effort.
 * See: .reference/20260107_Android_Refactor.md
 */
class HaliteVoiceController(
    private val context: Context,
    private val webViewProvider: () -> WebView,
    private val halitePrefs: HalitePreferences,
    private val screenDimmerProvider: () -> ScreenDimmer?,
    private val ttsProvider: () -> TextToSpeech?,
) {
    // Get the current WebView (may be recreated after memory pressure)
    private val webView: WebView
        get() = webViewProvider()
    companion object {
        private const val TAG = "HaliteVoice"
    }

    // Coroutine scope for async operations - cancelled in shutdown(), recreated on
    // the next init via ensureScopeActive(). Without that recreation a re-init after
    // a shutdown (e.g. sleep → wake) would launch initialize() on a cancelled scope —
    // a silent no-op — leaving sttManager null and STT broken until app restart.
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Capability lease (JS_KOTLIN_CONTRACTS #65 / #68) ───────────────────────
    //
    // Owned HERE rather than in VoicePipelineCoordinator (at its size budget) or MainActivity
    // (not voice's lifecycle). This class already owns `scope`, recreates it on re-init and
    // cancels it in shutdown(), which is exactly the lifetime the renewal loop needs: the loop
    // must run whenever voice is up and stop when it is torn down.
    //
    // ⚠️ Started AFTER the pipeline is constructed, so DegradedVoiceSink is installed before a
    // lapse can arrive. A lapse before installation is a safe no-op (nothing to degrade yet),
    // but ordering it this way means the common case never relies on that.
    private var capabilityLease: com.dashieapp.Dashie.halite.voice.lease.CapabilityLease? = null
    private var leaseRenewal: com.dashieapp.Dashie.halite.voice.lease.LeaseRenewalService? = null
    private var haEvents: com.dashieapp.Dashie.halite.ha.HaEventSubscriber? = null

    /**
     * Ensure [scope] is alive before launching init coroutines. shutdown() cancels it
     * permanently; a later checkPermissionAndInit()/reinitialize() must recreate it or
     * its scope.launch { initialize() } never runs (sttManager stays null → "Failed to
     * start STT session" after every sleep/wake).
     */
    private fun ensureScopeActive() {
        if (!scope.isActive) {
            Log.i(TAG, "🎤 Voice scope was cancelled — recreating for re-init")
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
    }

    // Voice service and components
    private var haVoiceService: HaVoiceService? = null
    private var voicePipelineCoordinator: VoicePipelineCoordinator? = null  // Multi-pathway voice (when useOverlayNlp=true)
    // HA-voice for the cloud-brain path (ttsProvider == va_default): synthesize
    // the brain's response text via HA's TTS engine (Piper/Azure) — FB26.
    private var haTtsSynthesizer: HaTtsSynthesizer? = null
    private var voiceIndicatorController: VoiceIndicatorController? = null
    private var haliteAudioBuffer: SharedAudioBuffer? = null
    private var haliteAudioCapture: AudioCaptureService? = null
    // WS-A.2 cascade AEC: cancels Dashie's own cloud-TTS reply out of the shared mic
    // (wake word + STT). Created per voice init; wired into AudioCaptureService
    // (capture) and ElevenLabsTtsClient (render reference). Non-fatal by design.
    private var cascadeAecCtl: com.dashieapp.Dashie.halite.voice.aec.CascadeAecController? = null
    private var haliteWakeWord: WakeWordDetectorInterface? = null
    private var haliteSampleCollector: LiveSampleCollector? = null
    private var haliteSampleUploader: SampleUploader? = null

    // Native cloud TTS for NATIVE-owned responses (kiosk AI-lane / cascade Dialog); see
    // speakNativeResponse(). Lazy — a logged-in device speaks via JS instead.
    // All native cloud-TTS (ElevenLabs / Inworld): vendor selection, warm-up, barge-in stop.
    // CR2: identify the account so the edge fn's pre-spend gate can 402; a 402 marks the
    // cached state exhausted → the prompt-to-choose. AEC: WS-A.2 PCM playback → AEC3 ref.
    private val cloudTtsRouter by lazy {
        com.dashieapp.Dashie.halite.voice.tts.CloudTtsRouter(
            context, halitePrefs,
            credentialProviderFn = {
                val c = halitePrefs.connection
                if (c.hasSupabaseJwt && !c.isSupabaseJwtExpired) c.supabaseJwt else null
            },
            onCreditDeniedFn = { CreditStateHolder.markExhausted() },
            aecControllerProviderFn = { cascadeAecCtl },
        )
    }
    // Local TTS box (Kokoro/OpenAI-compatible), used when ttsProvider=local_url (AEC tee as above).
    private val localTtsClient by lazy { com.dashieapp.Dashie.halite.voice.tts.LocalTtsClient(context).apply { aecControllerProvider = { cascadeAecCtl } } }
    // Device-engine TTS with an AEC render reference (WS-F.0b): synthesizeToFile → WAV → PCM tee.
    private val devicePcmSpeaker by lazy { com.dashieapp.Dashie.halite.voice.tts.DeviceTtsPcmSpeaker(context) { cascadeAecCtl } }

    /**
     * 🔑 THE one way to silence a reply. Every owner, every time.
     *
     * Six owners can be pushing audio out; "stop the reply" was hand-written at four sites,
     * each stopping a DIFFERENT subset (cloudTts · localTts · nativeResponse · ttsProvider ·
     * pcmSpeaker · the service's player):
     *   onCancelRequested        ✓✓✓✓✓✓
     *   onDialogBargeIn          ✓✓✓✓✓✗
     *   onWakeWordInterrupting   ✓✓✓✗✗✗   ← CASCADE barges only (see below)
     *   stopVoiceInteraction     ✗✗✗✗✗✓
     *
     * 🔴 The two owners the barge site missed are exactly the ones that render an HA-Assist
     * reply, so an honoured barge left the old answer talking into the new turn's LISTENING;
     * the device transcribed its own voice, the transcript came back empty, the turn died
     * (John's voice, vc189). Same class as the `isTtsSpeaking` mode-blindness and the
     * lock-stack capability oracle: code acting on ONE of several coexisting owners. Four
     * hand-mirrored copies is what let three of them drift apart unnoticed.
     *
     * ✏️ Two corrections from the 2026-08-26 lane audit, stated here because the table above
     * was written more confidently than the code deserved:
     *  - `onWakeWordInterrupting` is declared and invoked ONLY by `VoicePipelineCoordinator`,
     *    so it fires on CASCADE barges. An HA-Assist barge reaches this list through
     *    `HaVoiceService.stopVoiceInteraction` → `onStopAllSpeech` instead — which is why that
     *    wiring is load-bearing and not belt-and-braces.
     *  - There is a SEVENTH owner this does not stop: the JS-bridge/WebView TTS player, cut
     *    only by `onStopTts` in `handleWakeWordAccepted`. So the name overpromises. Folding it
     *    in wants the `onStopTts` seam, not another line here.
     * See .reference/HA_VOICE_PATHWAYS.md → "HA-Assist vs cascade" for the full audit.
     */
    fun stopAllSpeech(reason: String) {
        Log.i(TAG, "🔇 stopAllSpeech ($reason) — silencing every reply owner")
        runCatching { cloudTtsRouter.stop() }
        runCatching { localTtsClient.stop() }
        runCatching { nativeResponseTts.stop() }   // HA engine-direct (Piper) + HA pipeline players
        runCatching { ttsProvider()?.stop() }      // device-engine TTS
        runCatching { devicePcmSpeaker.stop() }    // WS-F.0b PCM playback
        runCatching { haVoiceService?.stopTtsPlayback() }  // the service's own HA-pipeline player
    }
    private fun warmCloudTts() {   // pre-warm cloud connection + edge fn (voice init + each wake)
        if (halitePrefs.voice.ttsProvider == com.dashieapp.Dashie.halite.preferences.VoicePreferences.TTS_DASHIE_CLOUD) cloudTtsRouter.warmUp()
    }
    // HA-local engine warmup (Piper voice model / whisper first-request) — idempotent
    // per engine+voice selection, so calling it from init AND reinitialize is cheap.
    private val localEngineWarmer by lazy { LocalEngineWarmer(halitePrefs) }
    private fun warmLocalEngines() { runCatching { localEngineWarmer.warmIfConfigured() } }

    // CR2/CR4 credit-boundary UI (prompt-to-choose + "Voice unavailable" toast). The
    // onExhausted registration is activity-lifetime, so it survives WebView recreation
    // (per the jsBridge-callbacks recreation rule) — every exhaustion signal (brain
    // degraded turn, TTS 402, STT reject, wake-time gate) funnels through it.
    // Credit-boundary surface via the edition seam. The two free-engine actions are carried
    // ACROSS the boundary (CreditBoundaryCallbacks) rather than assigned onto the commercial
    // class, because that class does not exist in the published edition — while
    // DegradedVoiceMode, which they drive, stays in main/: free engines are Chickadee's normal
    // mode, so only the credit TRIGGER is commercial.
    private val paywall: com.dashieapp.Dashie.edition.PaywallUi? by lazy {
        (context as? android.app.Activity)?.let { com.dashieapp.Dashie.edition.EditionSeams.paywall(it) }
    }

    // Permission launcher - must be set from Activity before use
    private var permissionLauncher: ActivityResultLauncher<String>? = null

    // Music ducking state - duck volume during voice interaction, restore after
    private var musicPausedForVoice = false
    private var lastTranscriptWasMusicCommand = false

    // Last TTS language tag applied to the local engine, so we don't call
    // setLanguage() (~100-150ms on the main thread) before every utterance —
    // only when the Language setting actually changes.
    private var lastAppliedTtsLanguage: String? = null

    // Callbacks
    var onVoiceWillInitialize: (() -> Unit)? = null  // Called BEFORE audio capture starts
    var onVoiceInitialized: (() -> Unit)? = null     // Called AFTER audio capture starts
    var onVoiceShutdown: (() -> Unit)? = null
    var onVoiceOverlayBridgeReady: ((VoiceOverlayBridge) -> Unit)? = null  // Called when overlay bridge is ready
    var onDuckMusic: (() -> Unit)? = null    // Duck ExoPlayer volume for voice (wake word/STT)
    var onUnduckMusic: (() -> Unit)? = null  // Restore ExoPlayer volume after voice
    var onPauseForTts: (() -> Unit)? = null  // Pause MA when AI response is about to speak (sidebar opens)
    var onStopTts: (() -> Unit)? = null      // Stop in-flight TTS playback on a new wake word (cloud TTS plays via the JS-bridge player, unreachable from the pipeline)
    var onWakeScreen: (() -> Unit)? = null   // Wake the screen on a wake word ("Hey Dashie" while the screen is off) — routes to HaliteScreenController.onVoiceWake()
    var onPlayConfirmationTone: (() -> Unit)? = null  // Play the confirmation chime for a command (instead of TTS) when one is selected

    // Local command interception providers (timer, music, volume)
    var isMusicPlayingProvider: (() -> Boolean)? = null
    var isAlarmPlayingProvider: (() -> Boolean)? = null
    var onStopAlarm: (() -> Unit)? = null  // Silence a ringing timer alarm (invoked on the wake word itself)
    var timerRemainingProvider: (() -> Int?)? = null
    var onVolumeUp: ((amount: Int) -> Unit)? = null
    var onVolumeDown: ((amount: Int) -> Unit)? = null
    var onSetVolume: ((level: Int) -> Unit)? = null
    var onTimerCommand: ((command: String, params: Map<String, Any>) -> Unit)? = null
    var onMusicPlayInitiated: (() -> Unit)? = null  // Clear dismissed flag when play command issued
    var onPlaybackModeCommand: ((command: String) -> Unit)? = null  // shuffle_on/off, repeat_one/all/off
    var onSpeakerMuteCommand: ((speakerName: String, muted: Boolean) -> Unit)? = null  // mute/unmute by spoken name
    var onPlayOnSpeaker: ((query: String, speakerName: String, params: Map<String, Any>) -> Unit)? = null  // play search targeted at speaker
    var onMusicCommand: ((command: String, paramsJson: String) -> Unit)? = null  // Route music commands via MA API
    var onVideoFeedCommand: ((command: String, params: Map<String, Any>) -> Unit)? = null  // Show/hide video feeds by voice
    var onScheduleCommand: ((command: String, params: Map<String, Any>) -> Unit)? = null  // Scheduled actions / reminders
    var onOpenAppCommand: ((command: String, params: Map<String, Any>) -> Unit)? = null  // Launch external apps by voice

    /**
     * Get the SharedAudioBuffer for sharing with RTSP streaming.
     * Returns null if voice has not been initialized yet.
     */
    fun getSharedAudioBuffer(): SharedAudioBuffer? {
        Log.i(TAG, "🔊 getSharedAudioBuffer called: id=${if (haliteAudioBuffer != null) System.identityHashCode(haliteAudioBuffer) else "null"}")
        return haliteAudioBuffer
    }

    /**
     * Live STT provider set for the benchmark harness (`?cmd=sttBench`).
     * Null until the multi-pathway pipeline has initialized.
     */
    fun sttManagerForBench(): com.dashieapp.Dashie.halite.voice.stt.SttProviderManager? =
        voicePipelineCoordinator?.sttManagerForBench()

    /**
     * The LIVE HA-Assist stack, for the HA-Assist turn bench (`?cmd=haAssistBench`) only.
     *
     * Three separate accessors rather than one bundle so a null says WHICH piece is missing —
     * `haVoiceService == null` means the device is in cascade mode and there is genuinely
     * nothing to bench, which is a different answer from "voice never initialized". Handed back
     * live (not captured) because the whole stack is rebuilt on voice re-init and on WebView
     * recreation.
     */
    fun haVoiceServiceForBench(): HaVoiceService? = haVoiceService
    fun sharedBufferForBench(): SharedAudioBuffer? = haliteAudioBuffer
    fun audioCaptureForBench(): AudioCaptureService? = haliteAudioCapture

    /**
     * Set the permission launcher for requesting microphone permission.
     * Must be called from Activity during onCreate, before any permission requests.
     */
    fun setPermissionLauncher(launcher: ActivityResultLauncher<String>) {
        permissionLauncher = launcher
    }

    /**
     * Set up the voice indicator controller.
     * Should be called after the Activity's content view is set.
     */
    fun setupVoiceIndicator(
        rootView: android.view.View,
        visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
    ) {
        voiceIndicatorController = VoiceIndicatorController()
        voiceIndicatorController?.attach(rootView, visibilityGate)
        voiceIndicatorController?.showResponses = halitePrefs.voice.showResponses
        voiceIndicatorController?.displayFormatProvider = { halitePrefs.voice.displayFormat }
        voiceIndicatorController?.resultIconProvider = {
            if (halitePrefs.voice.voiceControlMethod ==
                com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
            ) R.drawable.ic_dashie_logo_white
            else R.drawable.icon_homeassistant_blue
        }
        // When the thinking UI actually shows (after 1s delay), pause music so
        // the upcoming TTS response plays at full volume instead of ducked
        voiceIndicatorController?.onThinkingShown = {
            Log.i(TAG, "🎵 Thinking shown — pausing music for TTS")
            onPauseForTts?.invoke()
        }
        // User tapped the cancel (x) on the thinking card — abort the in-flight
        // request on whichever pathway is active (mirrors the wake-word
        // interrupt teardown). stopVoiceInteraction() returns the state machine
        // to IDLE, which unducks music via the onStateChanged callbacks.
        voiceIndicatorController?.onCancelRequested = {
            Log.i(TAG, "🎤 Cancel requested by user — aborting in-flight voice operation")
            // Cut the reply PLAYBACK too — the pipeline teardown below never touched the
            // native cloud/device TTS, so a cancelled reply finished its sentence
            // (on-device 2026-07-05). Same stop pair as the dialog barge-in.
            stopAllSpeech("user cancel")
            haVoiceService?.stopVoiceInteraction()
            voicePipelineCoordinator?.stopVoiceInteraction()
            // Tear down the conversation overlay too, so a cancelled turn's transcript
            // doesn't linger into the next exchange (Live + cascade Dialog). leaveUp=false
            // = clear now. The JS Dialog loop also ends on the interrupted speech-end.
            voiceIndicatorController?.endConversation(leaveResponseUp = false)
            if (musicPausedForVoice) {
                musicPausedForVoice = false
                onUnduckMusic?.invoke()
            }
        }
    }

    /**
     * Rebind the voice indicator's views to the current Activity content view (the
     * reattach seam for the "orange line / cards don't render after re-init" bug —
     * see [VoiceIndicatorController.attach]). Called from the registry's onResume;
     * idempotent (a no-op when already bound to this tree).
     */
    fun reattachVoiceIndicator(
        rootView: android.view.View,
        visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
    ) {
        voiceIndicatorController?.attach(rootView, visibilityGate)
    }

    /**
     * Update the showResponses preference on the voice indicator.
     */
    fun setShowResponses(show: Boolean) {
        voiceIndicatorController?.showResponses = show
    }

    /**
     * Update the thinking indicator with live brain progress ("Searching the web…",
     * "Finalizing…"). Bridged from JS (DashieNative.onVoiceProgress) via MainActivity.
     */
    fun updateProcessingStatus(status: String) {
        voiceIndicatorController?.updateProcessingStatus(status)
    }

    // ── Cascade conversation overlay (Dialog mode) ──────────────────────────────
    // Drive the SAME native overlay Live uses, from the JS conversation loop. Its
    // OverlayMode.CONVERSATION suppresses cascade chrome, so the loop routes the
    // listening line, transcript, cards, and teardown through here. Build plan §2.
    fun startConversationOverlay() {
        voiceIndicatorController?.startConversation()
    }
    fun setConversationOverlayListening(listening: Boolean) {
        voiceIndicatorController?.setConversationListening(listening)
    }
    fun conversationOverlayTranscript(speaker: String, text: String) {
        val sp = if (speaker.equals("user", ignoreCase = true)) {
            com.dashieapp.Dashie.voice.realtime.ConversationEngine.Speaker.USER
        } else {
            com.dashieapp.Dashie.voice.realtime.ConversationEngine.Speaker.DASHIE
        }
        voiceIndicatorController?.conversationTranscript(sp, text, null, true)
    }
    fun conversationOverlayCard(cardJson: String) {
        try {
            voiceIndicatorController?.conversationCard(org.json.JSONObject(cardJson))
        } catch (e: Exception) {
            Log.w(TAG, "conversationOverlayCard parse failed: ${e.message}")
        }
    }
    fun endConversationOverlay(leaveResponseUp: Boolean) {
        voiceIndicatorController?.endConversation(leaveResponseUp = leaveResponseUp)
    }

    fun onTtsSpeechEnd() {
        Log.i(TAG, "🔊 TTS speech ended — dismiss timer " + if (voicePipelineCoordinator?.isDialogActive() == true) "SKIPPED, dialog owns lifecycle (VoiceIndicatorController no-ops in CONVERSATION mode)" else "starting")
        voiceIndicatorController?.onTtsSpeechEnd()
        // Cascade Dialog (Engine B) re-arms the mic for the follow-up turn on TTS end.
        // No-op unless a cascade Dialog turn is awaiting TTS. Build plan §EngineB(C).
        voicePipelineCoordinator?.onCascadeTtsEnded()

        // Resume music immediately when TTS ends (don't wait for sidebar dismiss) —
        // EXCEPT mid-dialog: each turn's TTS end is not the end of the conversation,
        // and un-ducking here made every follow-up window listen over the music.
        // Dialog end restores it (endCascadeDialog → IDLE → the onStateChanged unduck).
        val dialogActive = voicePipelineCoordinator?.isDialogActive() == true
        if (musicPausedForVoice && !dialogActive) {
            musicPausedForVoice = false
            Log.i(TAG, "🎵 Resuming music immediately after TTS end")
            onUnduckMusic?.invoke()
        }

        // Return pipeline to IDLE after the post-speech reading buffer.
        // The indicator dismisses after 5s; resume wake word slightly after.
        // Not in a dialog (the loop owns its lifecycle), and re-checked at fire time —
        // the old fire-blind timer killed a LISTENING session started by a wake-word
        // interrupt within the 6s (truncated commands). Cancelled on wake accept too.
        voicePipelineCoordinator?.let { coordinator ->
            if (coordinator.getState() == VoicePipelineCoordinator.PipelineState.SPEAKING && !dialogActive) {
                pendingIdleComplete?.let { idleTimerHandler.removeCallbacks(it) }
                val complete = Runnable {
                    pendingIdleComplete = null
                    if (coordinator.getState() == VoicePipelineCoordinator.PipelineState.SPEAKING &&
                        !coordinator.isDialogActive()) {
                        coordinator.handleVoiceInteractionComplete()
                    }
                }
                pendingIdleComplete = complete
                idleTimerHandler.postDelayed(complete, 6000)
            }
        }
    }

    // Post-TTS idle-complete timer — held so a new interaction can cancel it (see onTtsSpeechEnd).
    private val idleTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingIdleComplete: Runnable? = null

    /**
     * FB28: JS-bridge entry to show a voice-feedback toast (e.g. "didn't understand") through
     * the SAME native card as the no-speech notice — so it's consistent and paints over native
     * overlays (music player). Runs on the UI thread; no-ops if the indicator isn't up yet.
     */
    fun showVoiceNotice(message: String, detail: String) {
        (context as? android.app.Activity)?.runOnUiThread {
            voiceIndicatorController?.showVoiceNotice(message, detail)
        }
    }

    /** Native HA command confirmation card (ported from the JS 'ha' toast). See
     *  [VoiceIndicatorController.showHaCommandResult]. */
    fun showHaCommandResult(message: String, command: String) {
        (context as? android.app.Activity)?.runOnUiThread {
            voiceIndicatorController?.showHaCommandResult(message, command)
        }
    }

    /** Scheduled-action creation confirmation card ("Reminder · Today at 5:12 PM"). See
     *  [VoiceIndicatorController.showScheduleConfirmation]. */
    fun showScheduleConfirmation(message: String, detail: String) {
        (context as? android.app.Activity)?.runOnUiThread {
            voiceIndicatorController?.showScheduleConfirmation(message, detail)
        }
    }

    /**
     * Update the mic muted state on the voice service.
     */
    fun setMicMuted(muted: Boolean) {
        halitePrefs.voice.micMuted = muted
        haVoiceService?.micMuted = muted
        voicePipelineCoordinator?.setMicMuted(muted)
        // Notify webapp of mute state change (bidirectional sync)
        webView.post {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('mic-muted-changed', { detail: { muted: $muted } }))",
                null
            )
        }
        Log.i(TAG, "🎤 Microphone muted = $muted")
    }

    /**
     * Duck audio for a voice interaction. Called by voice services via onWakeWordAccepted
     * only after the wake word passes all guards (not muted, not disabled, state is idle).
     */
    private fun duckForVoiceInteraction() {
        lastTranscriptWasMusicCommand = false
        musicPausedForVoice = true
        Log.i(TAG, "🎵 Ducking music for voice interaction")
        onDuckMusic?.invoke()
    }

    /**
     * Fired the instant a wake word passes all guards — before STT connects. Stops
     * in-flight TTS (so a spoken response doesn't bleed into the new STT), shows the
     * listening line immediately (no connect-latency lag, reliable after an interrupt
     * teardown — the later LISTENING state just re-asserts it), and ducks music.
     */
    private fun handleWakeWordAccepted() {
        onStopTts?.invoke()
        warmCloudTts()   // overlaps STT+brain → warm connection + edge fn by TTS time
        warmLocalEngines()   // same idea for HA-local Piper/whisper (idempotent + TTL'd)
        // Refresh the credit cache at the START of an interaction: the "Credits low" badge is
        // evaluated when this turn renders, and a turn routed HA-local/on-prem carries no credit
        // metadata to update it. Without this the badge shows whatever was cached — e.g. still
        // "low" after the user topped up on their phone. Throttled to 30s inside the reader, so
        // this is cheap even on back-to-back turns.
        runCatching {
            com.dashieapp.Dashie.edition.EditionSeams.credits(context).refreshBalance()
        }
        // A new interaction supersedes the previous turn's pending idle-complete — without
        // this the 6s timer fired mid-LISTENING and truncated the new command.
        pendingIdleComplete?.let { idleTimerHandler.removeCallbacks(it) }
        pendingIdleComplete = null
        (context as? android.app.Activity)?.runOnUiThread {
            onWakeScreen?.invoke()   // wake a dark screen first, so the line renders onto a lit display
            voiceIndicatorController?.show(HaVoiceService.VoiceState.LISTENING)
        }
        duckForVoiceInteraction()
    }

    /**
     * Update sample collection enabled state.
     */
    fun setSampleCollectionEnabled(enabled: Boolean) {
        Log.i(TAG, "🎤 Sample collection changed = $enabled")
        if (enabled) {
            haliteSampleUploader?.giveConsent()
            haliteSampleUploader?.setEnabled(true)
            haliteSampleCollector?.enabled = true
        } else {
            haliteSampleUploader?.setEnabled(false)
            haliteSampleCollector?.enabled = false
        }
    }

    /**
     * Attempt to recover license from server when voice is disabled.
     * This handles the case where user cleared storage but had a valid license.
     * If a license is found, enables voice and initializes voice control.
     */
    fun attemptRecoveryIfNeeded() {
        val voice = com.dashieapp.Dashie.edition.EditionSeams.voice(context, halitePrefs)

        // Already entitled: enable immediately, reconcile in the background. Matches the old
        // TRIAL_ACTIVE branch (enable now, ask the server about a licence upgrade after) and
        // the old ACTIVE branch (no server call at all) — refresh() itself no-ops when a valid
        // licence is stored, so the fast path stays fast without this call site knowing why.
        if (voice.isVoiceAllowed()) {
            Log.i(TAG, "🎤 Voice already allowed locally, enabling")
            halitePrefs.voice.voiceEnabled = true
            checkPermissionAndInit()
            return
        }

        // Not entitled locally — reconcile with the server, then re-ask.
        Log.i(TAG, "🎤 Voice not allowed locally, reconciling with server...")
        scope.launch {
            if (voice.isVoiceAllowed()) {
                Log.i(TAG, "🎤 Entitlement recovered — enabling voice")
                halitePrefs.voice.voiceEnabled = true
                checkPermissionAndInit()
            } else {
                Log.i(TAG, "🎤 No entitlement recovered, voice remains disabled")
            }
        }
    }

    /**
     * Check license status and request microphone permission for Halite voice control.
     * Called when voice is enabled.
     */
    fun checkPermissionAndInit() {
        // Skip if already initialized to prevent duplicate initialization
        if (isInitialized()) {
            Log.d(TAG, "🎤 Voice already initialized, skipping")
            return
        }

        ensureScopeActive()  // a prior shutdown() cancelled the scope; revive it so init coroutines run

        val voice = com.dashieapp.Dashie.edition.EditionSeams.voice(context, halitePrefs)

        // Already entitled: init immediately and reconcile in the background.
        //
        // The old code branched ACTIVE (init, never touch the server) vs everything-else
        // (always touch the server). That distinction now lives inside refresh(), which
        // no-ops when a valid licence is stored — so a licensed device still makes no network
        // Voice is free since 2026-08-02, so isVoiceAllowed() is unconditionally true and this
        // is the only path. The old else-branch reconciled with the licence server and re-asked;
        // both it and the server are gone. The CHECK stays rather than being inlined — see
        // VoiceEntitlement's note on why the question outlives its current answer.
        if (voice.isVoiceAllowed()) {
            Log.i(TAG, "🎤 Voice allowed, proceeding with init")
            proceedWithVoiceInit("")
        } else {
            // Unreachable today. Loud rather than silent, per standing rule 2: if some future
            // implementation CAN answer no, a voice toggle that quietly does nothing is the
            // exact failure this gate has always been able to produce.
            Log.w(TAG, "DROP: voice not allowed by the edition seam — voice will not initialise")
        }
    }

    /**
     * Proceed with voice initialization after license check passes.
     */
    private fun proceedWithVoiceInit(statusLabel: String) {
        Log.i(TAG, "🎤 Voice entitlement OK: ${statusLabel.ifEmpty { "(no status row in this edition)" }}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                    initialize()
                }
                else -> {
                    // Request permission
                    permissionLauncher?.launch(Manifest.permission.RECORD_AUDIO)
                        ?: Log.e(TAG, "🎤 Permission launcher not set!")
                }
            }
        } else {
            initialize()
        }
    }

    /**
     * Called when microphone permission is granted.
     * Should be called from the Activity's permission result handler.
     */
    fun onPermissionGranted() {
        Log.i(TAG, "🎤 Microphone permission granted for voice control")
        initialize()
    }

    /**
     * Called when microphone permission is denied.
     */
    fun onPermissionDenied() {
        Log.w(TAG, "🎤 Microphone permission denied - voice control disabled")
    }

    // Flag to prevent double initialization during async delay
    private var isInitializing = false

    /**
     * Initialize Halite voice control (HA Assist pipeline).
     * Uses Edge Impulse wake word detection and streams audio to HA.
     */
    fun initialize() {
        // Guard against double initialization
        if (isInitialized()) {
            Log.d(TAG, "🎤 Voice already initialized, skipping")
            return
        }
        if (isInitializing) {
            Log.d(TAG, "🎤 Voice initialization already in progress, skipping")
            return
        }
        isInitializing = true

        ensureScopeActive()  // revive the scope if a prior shutdown() cancelled it

        val haUrl = halitePrefs.connection.haUrl
        if (haUrl.isEmpty()) {
            Log.e(TAG, "🎤 Cannot initialize voice - no HA URL configured")
            isInitializing = false
            return
        }

        Log.i(TAG, "🎤 Initializing Halite voice control for HA: $haUrl")

        // Notify that voice is about to initialize - RTSP should stop to release audio HAL
        Log.i(TAG, "🎤 Notifying onVoiceWillInitialize - RTSP should stop now")
        onVoiceWillInitialize?.invoke()

        // Continue initialization after a delay to give RTSP time to release audio HAL
        // This prevents "status -22" error on devices with limited audio HAL (like Google TV)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            initializeAfterRtspRelease(haUrl)
        }, 600)
    }

    /**
     * Continue voice initialization after RTSP has released the audio HAL.
     * Split from initialize() to allow for async delay.
     */
    private fun initializeAfterRtspRelease(haUrl: String) {
        Log.i(TAG, "🎤 Continuing voice init after RTSP release delay...")

        // Create shared audio buffer (5 seconds)
        haliteAudioBuffer = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        Log.i(TAG, "🔊 Created SharedAudioBuffer: id=${System.identityHashCode(haliteAudioBuffer)}")

        // Create audio capture service (+ cascade AEC on its single mic seam, WS-A.2)
        cascadeAecCtl?.release()
        cascadeAecCtl = com.dashieapp.Dashie.halite.voice.aec.CascadeAecController(context)
        haliteAudioCapture = AudioCaptureService(context, haliteAudioBuffer!!).apply {
            captureProcessor = cascadeAecCtl
            onError = { error ->
                Log.e(TAG, "🎤 Audio capture error: $error")
            }
            onStarted = {
                Log.d(TAG, "🎤 Audio capture started")
            }
        }

        // Create sample uploader for anonymous training data contribution
        haliteSampleUploader = SampleUploader(context).apply {
            // If pref says enabled, ensure uploader consent is also given
            if (halitePrefs.voice.sampleCollectionEnabled) {
                giveConsent()
                setEnabled(true)
                Log.i(TAG, "🎤 Sample collection restored: enabled")
            }

            onUploadSuccess = { samplesToday, samplesTotal ->
                Log.i(TAG, "📦 Sample uploaded (today: $samplesToday, total: $samplesTotal)")
            }
            onUploadError = { error ->
                Log.w(TAG, "📦 Sample upload failed: $error")
            }
            onRateLimitReached = { message ->
                Log.i(TAG, "📦 Rate limit: $message")
            }
        }

        // Create sample collector (wire to uploader)
        haliteSampleCollector = LiveSampleCollector(haliteAudioBuffer!!, context).apply {
            enabled = halitePrefs.voice.sampleCollectionEnabled
            onSampleCollected = { wavData, metadataJson ->
                haliteSampleUploader?.queueSample(wavData, metadataJson)
            }
        }

        // Create wake word detector (engine selected by active model's version suffix)
        val modelManager = WakeWordModelManager(context)
        val activeModel = modelManager.getActiveModel()
        val engine = activeModel.engine
        Log.i(TAG, "Wake word model: ${activeModel.wakeWordName} ${activeModel.version} [${engine.displayName}]")

        // Sensitivity is applied per-engine below during detector creation

        haliteWakeWord = when (engine) {
            WakeWordEngine.EDGE_IMPULSE -> {
                val sensitivity = com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity
                    .fromString(halitePrefs.voice.mwwSensitivity)
                com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig.DETECTION_THRESHOLD =
                    activeModel.getCutoffForSensitivity(sensitivity)
                Log.i(TAG, "Creating EI-only detector (sensitivity: ${sensitivity.displayName}, " +
                        "threshold: ${com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseConfig.DETECTION_THRESHOLD})")
                EdgeImpulseDetector(context).also { eiDetector ->
                    eiDetector.sampleCollector = haliteSampleCollector
                }
            }
            WakeWordEngine.MICRO_WAKE_WORD -> {
                if (activeModel.isDualEngine) {
                    // Dual-engine (Hey Dashie / Chickadee) with sensitivity modes.
                    // Downloaded-model override is a hey_dashie artifact — models with
                    // their own EI leg (eiAssetPath set) use bundled assets only.
                    val modelFile = if (activeModel.eiAssetPath == null)
                        modelManager.getModelFile() else null
                    val sensitivity = com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity
                        .fromString(halitePrefs.voice.mwwSensitivity)
                    val sensitivityMode = when (sensitivity) {
                        com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity.HIGH ->
                            com.dashieapp.Dashie.wakeword.DualEngineDetector.SensitivityMode.HIGH
                        com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity.MEDIUM ->
                            com.dashieapp.Dashie.wakeword.DualEngineDetector.SensitivityMode.MEDIUM
                        com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity.LOW ->
                            com.dashieapp.Dashie.wakeword.DualEngineDetector.SensitivityMode.LOW
                    }
                    Log.i(TAG, "Creating ${activeModel.wakeWordName} dual detector " +
                            "(sensitivity: ${sensitivity.displayName})")
                    com.dashieapp.Dashie.wakeword.DualEngineDetector(
                        context = context,
                        mwwModelFile = modelFile,
                        mwwAssetModelPath = activeModel.assetPath,
                        eiAssetPath = activeModel.eiAssetPath,
                        dualThresholds = activeModel.dualThresholds,
                        agreementWindowMs = activeModel.agreementWindowMs
                    ).also { dualDetector ->
                        dualDetector.sensitivityMode = sensitivityMode
                        dualDetector.sampleCollector = haliteSampleCollector
                        // Tag collected samples with the active wake word so chickadee
                        // field clips never mix into the hey_dashie triage pool unlabeled.
                        haliteSampleCollector?.modelId = activeModel.modelId
                        haliteSampleCollector?.modelVersion = activeModel.version
                        // Barge-in: during TTS playback the gate relaxes to EI-only (MWW is
                        // echo-deaf). 🔴 BOTH modes — this asked only the cascade until
                        // 2026-08-26, so on HA-Assist it answered FALSE while HA's TTS was
                        // audibly playing and an EI fire fell through to the AND gate, where MWW
                        // is deaf to its own echo, and was vetoed. That was the SECOND of two
                        // independent reasons barge-in could not work there (the first: nothing
                        // was listening — see HaVoiceService.rearmWakeIfListeningIsOver). Fixing
                        // either alone would have looked like a fix and changed nothing.
                        // One predicate over both sources, not a second field: "is Dashie
                        // speaking" has one answer whichever coordinator owns the turn.
                        dualDetector.isTtsSpeaking = {
                            voicePipelineCoordinator?.getState() ==
                                VoicePipelineCoordinator.PipelineState.SPEAKING ||
                            haVoiceService?.getState() == HaVoiceService.VoiceState.SPEAKING
                        }
                    }
                } else if (!MicroFrontend.isAvailable()) {
                    Log.w(TAG, "microWakeWord not available on this ABI, falling back to Edge Impulse")
                    EdgeImpulseDetector(context)  // No sample collection for non-Hey-Dashie models
                } else {
                    // Other MWW models: apply sensitivity as cutoff
                    val modelFile = modelManager.getModelFile()
                    val sensitivity = com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity
                        .fromString(halitePrefs.voice.mwwSensitivity)
                    MicroWakeWordConfig.probabilityCutoff = activeModel.getCutoffForSensitivity(sensitivity)
                    MicroWakeWordConfig.slidingWindowSize = activeModel.slidingWindowSize
                    Log.i(TAG, "Creating MWW detector (sensitivity: ${sensitivity.displayName}, " +
                            "cutoff: ${MicroWakeWordConfig.probabilityCutoff})")
                    MicroWakeWordDetector(
                        context = context,
                        modelFile = modelFile,
                        assetModelPath = activeModel.assetPath
                    ).also { mwwDetector ->
                        // Hey Dashie variants still feed training-data collection;
                        // other wake words never collect (hey_dashie-only corpus).
                        if (activeModel.wakeWordName.startsWith(WakeWordModel.NAME)) {
                            mwwDetector.sampleCollector = haliteSampleCollector
                        }
                    }
                }
            }
        }

        haliteWakeWord!!.apply {
            setSharedBuffer(haliteAudioBuffer!!)

            // Send heartbeat (2x/sec) to webapp + native settings meter
            onHeartbeat = { confidence, volume ->
                // Update static holder for native settings confidence bar
                com.dashieapp.Dashie.halite.settings.fragments.LiveConfidenceHolder.update(confidence)

                webView.post {
                    webView.evaluateJavascript(
                        "if(typeof window.onDashieWakeWordHeartbeat==='function'){window.onDashieWakeWordHeartbeat($confidence,$volume)}",
                        null
                    )
                }
            }

            onWakeWordDetected = { confidence, bufferPosition ->
                Log.i(TAG, "🎤 Wake word detected! Confidence: ${"%.1f".format(confidence * 100)}%")

                // Mark threshold reached for settings confidence bar
                com.dashieapp.Dashie.halite.settings.fragments.LiveConfidenceHolder.markThresholdReached()

                if (com.dashieapp.Dashie.halite.settings.fragments.LiveConfidenceHolder.suppressDetection) {
                    Log.i(TAG, "🎤 Detection suppressed (settings test mode)")
                } else {
                    // A ringing timer alarm is silenced by ANY voice interaction: the
                    // wake word itself stops it, so the user never has to shout over a
                    // blaring alarm to issue the "stop" command. The voice session then
                    // continues normally (a follow-up "stop the alarm" no-ops).
                    if (isAlarmPlayingProvider?.invoke() == true) {
                        Log.i(TAG, "⏰ Wake word during ringing alarm — silencing alarm")
                        onStopAlarm?.invoke()
                    }

                    // Forward to active voice service
                    haVoiceService?.onWakeWordDetected(confidence, bufferPosition)
                    voicePipelineCoordinator?.onWakeWordDetected(confidence, bufferPosition)
                }
            }

            onError = { error ->
                Log.e(TAG, "🎤 Wake word error: $error")
            }
        }

        // Initialize wake word detector
        if (haliteWakeWord?.initialize() != true) {
            Log.e(TAG, "🎤 Failed to initialize wake word detector")
            isInitializing = false
            return
        }

        // Choose voice pathway based on useOverlayNlp setting
        val useOverlay = halitePrefs.voice.useOverlayNlp
        val pipelineMode = halitePrefs.voice.voicePipelineMode
        Log.i(TAG, "🎤 Pipeline decision: useOverlayNlp=$useOverlay, voicePipelineMode=$pipelineMode")

        if (useOverlay) {
            initializeVoicePipeline(haUrl)
        } else {
            LeaseMarkers.fallbackEmit = { line -> Log.i(LeaseMarkers.TAG, line) }
            LeaseMarkers.markNotStarted("HA-Assist lane — the box spends on its own behalf")
            // 🔴 NOT_APPLICABLE, not "refused" — and the distinction is the whole reason this
            // state exists. Nothing was withheld here and nothing was asked; the lease simply
            // does not govern this lane. A UI that rendered "Not shared" for it would be stating
            // a falsehood that reads perfectly plausibly.
            com.dashieapp.Dashie.halite.voice.lease.LeaseStateHolder.onNotApplicable()
            initializeHaVoiceService(haUrl)
        }

        // Start audio capture and wake word detection
        haliteAudioCapture?.start()
        haliteWakeWord?.start()

        Log.i(TAG, "🎤 Halite voice control initialized - listening for 'Hey Dashie'")
        isInitializing = false
        onVoiceInitialized?.invoke()
    }

    /**
     * Initialize voice using VoicePipelineCoordinator (multi-pathway STT + Overlay NLP).
     * Used when useOverlayNlp preference is enabled.
     */
    private fun initializeVoicePipeline(haUrl: String) {
        Log.i(TAG, "🎤 Using VoicePipelineCoordinator (Overlay NLP mode)")

        // FB26: warm an HA-TTS voice for the cloud-brain path so a va_default
        // ttsProvider can speak the brain's response with the HA pipeline voice.
        // Only meaningful when HA is reachable; harmless otherwise (speak()
        // falls back via onError → device TTS).
        haTtsSynthesizer?.release()
        haTtsSynthesizer = HaTtsSynthesizer(
            context = context,
            haUrl = haUrl,
            haBaseUrl = halitePrefs.connection.haBaseUrl.ifEmpty { haUrl },
            haToken = { halitePrefs.connection.haAccessToken },   // live — token rotates ~30 min
            pipelineId = halitePrefs.voice.voicePipelineId.takeIf { it.isNotEmpty() },
            aecControllerProvider = { cascadeAecCtl }             // WS-F.0b render reference
        ).also { it.connect() }

        voicePipelineCoordinator = VoicePipelineCoordinator(context, webViewProvider, halitePrefs).apply {
            // Set up immediately (not in coroutine) so wake word can work right away
            setSharedBuffer(haliteAudioBuffer!!)
            setAudioCaptureService(haliteAudioCapture!!)
            setWakeWordDetector(haliteWakeWord!!)
            // The pipeline engages the AEC for the whole turn (not just while Dashie speaks)
            // so STT/VAD read noise-suppressed audio — CascadeAecController.onTurnStart.
            aecControllerProvider = { cascadeAecCtl }
            setEnabled(true)
            setMicMuted(halitePrefs.voice.micMuted)
            Log.i(TAG, "🎤 VoicePipelineCoordinator enabled immediately")

            startCapabilityLease()

            // Initialize STT providers in coroutine (can be async)
            scope.launch {
                initialize()

                // State change callback - map to VoiceIndicatorController
                onStateChanged = { pipelineState, subtitle ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        // Map VoicePipelineCoordinator state to HaVoiceService.VoiceState for UI
                        val uiState = when (pipelineState) {
                            VoicePipelineCoordinator.PipelineState.IDLE -> HaVoiceService.VoiceState.IDLE
                            VoicePipelineCoordinator.PipelineState.CONNECTING -> HaVoiceService.VoiceState.CONNECTING
                            VoicePipelineCoordinator.PipelineState.LISTENING -> HaVoiceService.VoiceState.LISTENING
                            VoicePipelineCoordinator.PipelineState.PROCESSING -> HaVoiceService.VoiceState.PROCESSING
                            VoicePipelineCoordinator.PipelineState.SPEAKING -> HaVoiceService.VoiceState.SPEAKING
                            VoicePipelineCoordinator.PipelineState.ERROR -> HaVoiceService.VoiceState.ERROR
                        }
                        // DLG-6: a re-armed follow-up listen shows ONLY the bottom orange bar (no dim
                        // backdrop, keeps the prior response) — not the full listening overlay.
                        if (voicePipelineCoordinator?.isAnnouncementTurn() == true) {
                            // A SCHEDULED action is running: its reminder card IS the surface. The
                            // voice overlay (dim backdrop + panel) is conversation UI — nobody asked
                            // a question — and showing both made the card look like it was floating
                            // on a spurious voice session.
                        } else if ((pipelineState == VoicePipelineCoordinator.PipelineState.LISTENING ||
                                pipelineState == VoicePipelineCoordinator.PipelineState.CONNECTING) &&
                            voicePipelineCoordinator?.isReArmedFollowUp() == true) {
                            voiceIndicatorController?.showListeningMinimal()
                        } else {
                            // The indicator self-arbitrates (ignores cascade calls during a
                            // live conversation), so this can fire unconditionally.
                            voiceIndicatorController?.show(uiState, subtitle)
                        }
                        // Wake screen when voice activity starts
                        if (pipelineState != VoicePipelineCoordinator.PipelineState.IDLE) {
                            screenDimmerProvider()?.resetTimer()
                        }

                        // Unduck music when voice interaction completes or errors
                        if ((pipelineState == VoicePipelineCoordinator.PipelineState.IDLE ||
                             pipelineState == VoicePipelineCoordinator.PipelineState.ERROR) &&
                            musicPausedForVoice) {
                            musicPausedForVoice = false
                            Log.i(TAG, "🎵 Unducking music after voice pipeline complete")
                            onUnduckMusic?.invoke()
                        }
                    }
                }

                // STT result callback
                onSttResult = { text ->
                    Log.i(TAG, "🎤 STT result: $text")
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.showSttToast(text)
                    }
                }

                // HA fast-path confirmation → the native HA command card (same widget the JS 'ha'
                // toast draws), instead of the conversation overlay for a locally-handled device command.
                onHaCommandResult = { message, command -> showHaCommandResult(message, command) }
                onLocalMusicResult = { message, command ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.showMusicResult(message, command)
                    }
                }

                // Realtime conversation mode UI (build plan §3.1): the transcript
                // panel + listening(orange line)/thinking(dots) indicator.
                onConversationStart = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.startConversation()
                    }
                }
                onConversationState = { state ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        when (state) {
                            com.dashieapp.Dashie.voice.realtime.ConversationEngine.State.LISTENING,
                            com.dashieapp.Dashie.voice.realtime.ConversationEngine.State.INTERRUPTED ->
                                voiceIndicatorController?.setConversationListening(true)   // orange line
                            com.dashieapp.Dashie.voice.realtime.ConversationEngine.State.CONNECTING,
                            com.dashieapp.Dashie.voice.realtime.ConversationEngine.State.SPEAKING ->
                                voiceIndicatorController?.setConversationListening(false)  // dots
                            else -> { /* CLOSED/ERROR → onConversationEnd */ }
                        }
                    }
                }
                onConversationTranscript = { speaker, text, additionalText, isFinal ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.conversationTranscript(speaker, text, additionalText, isFinal)
                    }
                }
                onConversationCard = { card ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.conversationCard(card)
                    }
                }
                onConversationEnd = { idleClose ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        // Idle timeout → leave the last response up (auto-dismisses on the
                        // normal reading timer); manual/error close → tear down now.
                        voiceIndicatorController?.endConversation(leaveResponseUp = idleClose)
                    }
                }

                // Speak a NATIVE-owned response (kiosk AI-lane brain / cascade Dialog).
                // Distinct from onTtsRequest: JS never speaks this, so always voice it via
                // the account's cloud voice; onDone drives the cascade follow-up re-arm.
                onSpeakResponse = { text, voiceId, voiceProvider, sessionId, onDone ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        speakNativeResponse(text, voiceId, voiceProvider, sessionId, onDone)
                    }
                }

                // TTS request callback — only use native TTS when JS isn't handling it
                // In cloud/AI mode, the JS side calls ElevenLabs TTS directly
                onTtsRequest = { text, isCommand ->
                    val isCloudMode = halitePrefs.voice.voicePipelineMode ==
                        com.dashieapp.Dashie.halite.preferences.VoicePreferences.VOICE_PIPELINE_MODE_AI
                    if (isCloudMode) {
                        Log.i(TAG, "🎤 Skipping local TTS — JS/ElevenLabs handles TTS in cloud mode")
                    } else {
                        Log.i(TAG, "🎤 Speaking via local TTS: $text")
                        (context as? android.app.Activity)?.runOnUiThread {
                            speakWithLocalTts(text, isCommand)
                        }
                    }
                }

                // Error callback
                onError = { error ->
                    Log.e(TAG, "🎤 Voice pipeline error: $error")
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.show(HaVoiceService.VoiceState.ERROR, error)
                    }
                }

                // Muted wake word callback
                onMicMutedWakeWord = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, "Microphone is muted", Toast.LENGTH_SHORT).show()
                    }
                }

                // Duck audio only after wake word passes all guards (not muted, not disabled, idle)
                onWakeWordAccepted = { handleWakeWordAccepted() }

                // CR2/CR4: markExhausted → onExhausted → showPrompt owns BOTH the prompt and
                // the "Don't show this again" fallback toast (FB27), so onVoiceUnavailable is a
                // no-op (avoids a toast+prompt double). onExhausted is controller-lifetime.
                onVoiceUnavailable = { }
                CreditStateHolder.onExhausted = { balance -> paywall?.showCreditPrompt(balance) }
                // WS-D.1: persistent visible state while running on free engines, and the one-time
                // spoken reason. Never degrade silently — a tablet that quietly gets worse reads as
                // broken, and the user never learns credits are the fix.
                onDegradedChanged = { degraded ->
                    paywall?.setCreditDegraded(degraded)
                    CreditStateHolder.setDegraded(degraded)
                }
                onSpeakDegradedNotice = { line -> speakNativeResponse(line, null, null, null) {} }
                // Punch #5: the CR2 chooser's "Use local voice" now PERSISTS the free config
                // (applies the free preset + rounds it up so the console/Settings reflect it),
                // then reinitializes the pipeline so the new engines take effect immediately.
                // No runtime override, no auto-restore — the user stays local until they switch
                // back in Settings. (The temporary "Not now" / "Don't show again" paths still use
                // the runtime DegradedVoiceMode override.) Returns false when this device has no
                // free voice, so the chooser can stay honest and point at Settings.
                // Both return Boolean and the value is load-bearing: false = "no free engine
                // on this device", which the chooser reads before claiming the switch happened.
                paywall?.attachCreditBoundary(
                    com.dashieapp.Dashie.edition.CreditBoundaryCallbacks(
                        onUseLocalVoice = {
                            val persisted = LocalVoiceSwitch.persist(context) != null
                            if (persisted) {
                                reinitialize()
                                // reinitialize() refreshes billability, but no-ops if voice isn't
                                // active yet / bails with no HA URL — refresh here too so the pill
                                // always hides.
                                CreditStateHolder.setBillable(halitePrefs.voice.isBillableVoice)
                                paywall?.updateLowCreditPill()
                            }
                            persisted
                        },
                        // Punch #5: the chooser's TEMPORARY paths — "Not now" (24h) and "Don't
                        // show again" (no expiry) — apply the in-memory runtime
                        // DegradedVoiceMode override (auto-restores on credits/restart/expiry),
                        // NOT a persisted config change.
                        onDeferToLocalVoice = { expiresInMs ->
                            fallBackToLocalVoiceTemporarily(expiresInMs)
                        },
                    )
                )
                // FB27: keep the dismissible low-credit pill in sync with the credit cache.
                CreditStateHolder.addOnChanged("creditPill") { paywall?.updateLowCreditPill() }
                // FB27: credit UI only applies to a billable (cloud) voice pipeline.
                CreditStateHolder.setBillable(halitePrefs.voice.isBillableVoice)
                paywall?.updateLowCreditPill()
                // Kick a balance read so the pill reflects a low balance without needing a
                // voice turn / CC open first (throttled; no-op if already fresh).
                com.dashieapp.Dashie.edition.EditionSeams.credits(context).refreshBalance()

                // FB19: silent listen window → subtle small-card notice (no red dot).
                onNoSpeechNotice = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        voiceIndicatorController?.showNoSpeechNotice()
                    }
                }

                // Narrower than onWakeWordInterrupting below — the conversation continues (no
                // indicator dismiss, music stays ducked). The stopped ElevenLabs request's
                // onDone → onTtsSpeechEnd is a no-op via its dialog guards.
                onDialogBargeIn = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Log.i(TAG, "🎤 Dialog barge-in — stopping reply playback")
                        stopAllSpeech("dialog barge-in")
                    }
                }

                onBrainProgress = { voiceIndicatorController?.updateBrainProgress(it) }

                // When wake word interrupts an in-flight interaction, dismiss any
                // open sidebar/result card so it doesn't linger
                onWakeWordInterrupting = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Log.i(TAG, "🎤 Wake word interrupting — force-dismissing voice indicator")
                        // 🔴 THE defect-4 site: this used to stop 3 of 6 owners, missing the two
                        // that actually render an HA-Assist reply, so the old answer talked into
                        // the new turn's LISTENING and killed it.
                        stopAllSpeech("wake word interrupting")
                        voiceIndicatorController?.forceDismiss()
                        // Also resume any paused music since we're starting fresh
                        if (musicPausedForVoice) {
                            musicPausedForVoice = false
                            onUnduckMusic?.invoke()
                        }
                    }
                }

                // Notify that the overlay bridge is ready for wiring to DashieJSBridge
                getOverlayBridge()?.let { bridge ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        onVoiceOverlayBridgeReady?.invoke(bridge)
                    }
                }
            }
        }
        // Boot warmup: pre-warm the cloud-TTS connection + edge fn once at init so the
        // FIRST turn after launch isn't slowed by a cold handshake / cold edge fn.
        warmCloudTts()
        // Same for HA-local engines: load the Piper voice model / prime whisper now,
        // not on the first real reply (the "long first-voice lag", 2026-07-12).
        warmLocalEngines()
    }

    /**
     * Mic swap for HA Voice Assist's local STT stage, so "On-Device (Built-in)" can register and
     * actually RUN here — before 2026-08-24 the stage passed null, the chain ranked the rung
     * anyway, and the turn silently went to HA's `faster_whisper`.
     *
     * `reArmWakeWord = null` is the load-bearing argument: HA-Assist's turn continues past STT,
     * so `HaVoiceService` owns the re-arm. See [SharedCaptureMicHandoff]'s table.
     */
    private val haAssistSttMicHandoff = SharedCaptureMicHandoff(
        capture = { haliteAudioCapture },
        wakeWord = { haliteWakeWord },
        handler = android.os.Handler(android.os.Looper.getMainLooper()),
        reArmWakeWord = null,
        tag = TAG,
    )

    /**
     * Initialize voice using HaVoiceService (HA Assist STT + Intent).
     * Used when useOverlayNlp preference is disabled (default).
     */
    private fun initializeHaVoiceService(haUrl: String) {
        Log.i(TAG, "🎤 Using HaVoiceService (HA Assist mode)")

        haVoiceService = HaVoiceService(context, webViewProvider, haUrl).apply {
            aecControllerProvider = { cascadeAecCtl }   // WS-F.0b: set BEFORE initialize() builds the player
            initialize()
            setSharedBuffer(haliteAudioBuffer!!)
            setAudioCaptureService(haliteAudioCapture!!)
            setWakeWordDetector(haliteWakeWord!!)
            setEnabled(true)

            // HA-Assist path TTS source = the ttsProvider picker (FB26): only
            // "Voice Assistant Default" (va_default) uses the HA pipeline voice
            // (Piper/Azure); Android Voice / Dashie Cloud use device/ElevenLabs.
            // Retires the disconnected useHaForTts boolean.
            useLocalTts = halitePrefs.voice.ttsProvider !=
                com.dashieapp.Dashie.halite.preferences.VoicePreferences.TTS_VA_DEFAULT

            // #64: let the per-turn settings line name the engine that will actually speak.
            // Read live through the router, so it reflects a degraded override too.
            describeTtsEngine = { nativeResponseTts.describeResolvedEngine() }

            // Apply mic muted preference
            micMuted = halitePrefs.voice.micMuted

            // On-device / engine-direct STT for this mode. Before 2026-07-29 HA Voice Assist
            // ALWAYS streamed audio for HA to transcribe, so a local sttProvider (sherpa, HA
            // engine-direct Whisper, own-box) was silently ignored — and when HA's pipeline had no
            // stt_engine, every turn failed. Built async (createProviders is suspend) and attached
            // when ready; until then the service streams audio exactly as before.
            val service = this
            ensureScopeActive()
            scope.launch {
                val stage = HaLocalSttStage(
                    context, halitePrefs,
                    android.os.Handler(android.os.Looper.getMainLooper()),
                    micHandoff = haAssistSttMicHandoff,
                )
                stage.prepare()
                service.localSttStage = stage
                Log.i(TAG, "🎤 HA Assist local STT stage ready " +
                    "(localSttAvailable=${stage.localSttAvailable()})")
            }

            // Transcript interceptor for local command handling (timer, music, volume)
            onTranscriptInterceptor = { text ->
                checkLocalCommandInterception(text)
            }
            // Punch #4: answer weather natively in the Voice Assist (HA Assist) pipeline too —
            // HA can't do weather. Same executor the cascade / Live use (WeatherVoiceTool).
            onWeatherQuery = { args ->
                WeatherVoiceTool.query(context, args)
                    .optJSONObject("result")?.optString("voice").orEmpty()
            }

            // State change callback - update UI and wake screen
            onStateChanged = { state, subtitle ->
                (context as? android.app.Activity)?.runOnUiThread {
                    voiceIndicatorController?.show(state, subtitle)
                    // Wake screen when voice activity starts (listening, thinking, speaking)
                    if (state != HaVoiceService.VoiceState.IDLE) {
                        screenDimmerProvider()?.resetTimer()
                    }

                    // Always unduck (restore volume) when voice interaction completes or errors
                    if ((state == HaVoiceService.VoiceState.IDLE || state == HaVoiceService.VoiceState.ERROR) && musicPausedForVoice) {
                        musicPausedForVoice = false
                        Log.i(TAG, "🎵 Unducking music after voice interaction (musicCommand=$lastTranscriptWasMusicCommand)")
                        onUnduckMusic?.invoke()
                    }
                }
            }

            // STT result callback - store command for display with response
            onSttResult = { text ->
                Log.i(TAG, "🎤 STT result: $text")
                (context as? android.app.Activity)?.runOnUiThread {
                    voiceIndicatorController?.showSttToast(text)
                }
            }

            // Teardown must silence EVERY reply owner, not just the service's own player —
            // the controller owns the ones that actually speak on this path.
            onStopAllSpeech = { stopAllSpeech("HaVoiceService teardown") }

            // Intent result callback (response is now passed via state change subtitle)
            onIntentResult = { intent, response ->
                Log.i(TAG, "🎤 HA Intent: $intent, Response: $response")
                // Response display is now handled by state change with subtitle
            }

            // Local TTS request callback - speak using device TTS
            onTtsRequest = { text, isCommand ->
                Log.i(TAG, "🎤 Speaking via local TTS: $text")
                (context as? android.app.Activity)?.runOnUiThread {
                    speakWithLocalTts(text, isCommand)
                }
            }

            // Error callback
            onError = { error ->
                Log.e(TAG, "🎤 Voice error: $error")
                (context as? android.app.Activity)?.runOnUiThread {
                    voiceIndicatorController?.show(HaVoiceService.VoiceState.ERROR, error)
                }
            }

            // Muted wake word callback - show toast to inform user
            onMicMutedWakeWord = {
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(
                        context,
                        "Microphone is muted",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Duck audio only after wake word passes all guards (not muted, not disabled, idle)
            onWakeWordAccepted = { handleWakeWordAccepted() }
        }
    }

    /**
     * Speak a reply the HA-Assist lane owns (an Assist answer, or a locally-intercepted
     * command ack) — through the SAME router the cascade uses.
     *
     * 🔀 2026-08-26: this used to call [speakDeviceTts] directly, so `voice.ttsProvider` was
     * honoured on the cascade and silently ignored here — a user who picked Piper got the
     * Android engine, with zero attempts and zero warnings, because the setting was never
     * consulted rather than failing over. (John found it by ear; `HaAssistStagePlanner` had
     * already done its half, ending HA's run at `intent` precisely BECAUSE the user wanted a
     * device/engine-direct voice, and then this lane discarded which one.)
     *
     * ⚠️ **`onSpeechEnd` is deliberately empty here.** `HaVoiceService` owns this lane's
     * turn-end: [notifyTtsComplete] → `onLocalTtsComplete()` → `handleVoiceInteractionComplete()`.
     * The cascade's `onTtsSpeechEnd()` would ALSO re-arm and restart dismiss timers — that is
     * double-handled turn-end, the shape of the last three defects, so the seam is stated at
     * the call site rather than assumed by the router.
     */
    private fun speakWithLocalTts(text: String, isCommand: Boolean = false) =
        nativeResponseTts.speak(
            text = text,
            brainVoiceId = null,
            isCommand = isCommand,
            onSpeechEnd = { },                      // HaVoiceService owns turn-end on this lane
            onDone = { notifyTtsComplete() },
        )

    /**
     * Speak [text] via the on-device TextToSpeech engine, invoking [onComplete] when speech
     * finishes/errors/skips, and [onStart] at real first audio. Reached only through
     * [NativeResponseTts] — as the device branch, or as any branch's failure fallback — so
     * the response-handling gates below are the router's belt-and-braces, and the
     * confirmation-tone gate that used to live here is now hoisted ABOVE the engine branch
     * ([TtsRoutePlan.speaksToneInsteadOfWords]); a copy here would be a second owner of one
     * rule, which is the defect class this change exists to close.
     * In the native (overlay) pipeline haVoiceService is null, so the HA-fallback branches
     * reduce to [onComplete].
     */
    private fun speakDeviceTts(text: String, onStart: () -> Unit, onComplete: () -> Unit) {
        // Check response handling mode (from Voice Pipeline Settings)
        val responseHandling = halitePrefs.voice.responseHandling
        if (responseHandling == "display_only" || responseHandling == "none") {
            Log.i(TAG, "🔊 TTS skipped - response handling is $responseHandling")
            onComplete()
            return
        }

        // Check if Read Responses Aloud is enabled
        if (!halitePrefs.voice.readResponsesAloud) {
            Log.i(TAG, "🔊 TTS skipped - Read Responses Aloud is disabled")
            onComplete()
            return
        }

        val tts = ttsProvider()
        if (tts == null) {
            Log.e(TAG, "🎤 Local TTS not initialized, falling back to HA pipeline TTS")
            DiagnosticBuffer.error("TTS", "speak() failed: TTS engine is null, falling back to HA pipeline")
            haVoiceService?.onLocalTtsFailed() ?: onComplete()
            return
        }

        Log.i(TAG, "🔊 TTS speak request: '$text'")
        Log.i(TAG, "🔊 TTS engine: ${tts.defaultEngine}")
        DiagnosticBuffer.info("TTS", "speak(): engine=${tts.defaultEngine}, text=${text.take(50)}")

        // Apply the configured language only when it actually changes. Calling
        // setLanguage() before every utterance adds ~100-150ms of main-thread
        // voice re-selection before speech starts. TtsManager applies it at
        // init; this just catches a live Language-setting change for the next
        // response (the first Spanish utterance still pays the one-time voice
        // load inside the engine). Hoisted above the WS-F.0b branch so the
        // synthesizeToFile route speaks the configured language too.
        val ttsLangTag = com.dashieapp.Dashie.halite.preferences.GeneralPreferences(context).language
        if (ttsLangTag != lastAppliedTtsLanguage) {
            runCatching {
                com.dashieapp.Dashie.halite.TtsLocaleResolver.apply(tts, ttsLangTag)
            }
            lastAppliedTtsLanguage = ttsLangTag
        }

        // WS-F.0b: AEC-teed route — synthesizeToFile → WAV → PcmTtsPlayer with a render
        // reference for the cascade AEC. tts.speak() plays inside the engine (no PCM →
        // echo-blind self-hearing). Every failure falls back to the legacy route below.
        val speakLegacy = { speakDeviceTtsLegacy(tts, text, onStart, onComplete) }
        if (devicePcmSpeaker.trySpeak(tts, text, onDone = onComplete, fallback = speakLegacy, onStart = onStart)) return
        speakLegacy()
    }

    /** The pre-F.0b device-TTS route: engine-internal playback (no AEC render reference). */
    private fun speakDeviceTtsLegacy(
        tts: android.speech.tts.TextToSpeech,
        text: String,
        onStart: () -> Unit,
        onComplete: () -> Unit,
    ) {
        // Set up utterance listener to know when speech completes
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "🔊 TTS onStart: $utteranceId")
                onStart()   // real first audio → the device engine's stage timing
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "🔊 TTS onDone: $utteranceId")
                (context as? android.app.Activity)?.runOnUiThread {
                    onComplete()
                }
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "🔊 TTS onError (deprecated): $utteranceId")
                (context as? android.app.Activity)?.runOnUiThread {
                    onComplete()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "🔊 TTS onError: $utteranceId, code=$errorCode")
                DiagnosticBuffer.error("TTS", "Utterance error: code=$errorCode, id=$utteranceId")
                (context as? android.app.Activity)?.runOnUiThread {
                    onComplete()
                }
            }
        })

        // Speak the text (language already applied by speakDeviceTts before the branch)
        val utteranceId = "ha_voice_response_${System.currentTimeMillis()}"
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "🔊 TTS speak() result: $result (SUCCESS=${TextToSpeech.SUCCESS}, ERROR=${TextToSpeech.ERROR})")

        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "🔊 Failed to start TTS, result=$result, falling back to HA pipeline TTS")
            DiagnosticBuffer.error("TTS", "speak() returned error=$result, engine=${tts.defaultEngine}, falling back to HA pipeline")
            haVoiceService?.onLocalTtsFailed() ?: onComplete()
        }
    }

    /**
     * Speak a NATIVE-owned response (kiosk AI-lane brain / cascade Dialog). Unlike the
     * overlay path, JS will NOT speak this, so we always voice it: the account's cloud
     * voice (ElevenLabs, same as a logged-in device) when TTS provider is Dashie Cloud,
     * else the device engine. [onDone] fires once when speech ends (drives the cascade
     * follow-up re-arm) — and we also route through onTtsSpeechEnd() so the indicator
     * dismiss timer + VoicePipelineCoordinator.onCascadeTtsEnded() stay in sync.
     */
    // Routes a native-owned response to the picked TTS engine (ElevenLabs /
    // HA pipeline voice / device). Lives in its own file — FB26. Recreated per
    // pipeline init so it captures the current haTtsSynthesizer.
    private val nativeResponseTts by lazy {
        NativeResponseTts(
            context = context,
            prefs = halitePrefs,
            cloudTts = cloudTtsRouter,
            localTts = { localTtsClient },
            haTts = { haTtsSynthesizer },
            deviceTts = { text, onStart, onComplete -> speakDeviceTts(text, onStart, onComplete) },
            // WS-F.0b render reference. Despite the name this AEC controller is NOT
            // cascade-scoped: it is built in initializeAfterRtspRelease, before either
            // pipeline is chosen, and attached to the single AudioCaptureService mic seam —
            // HaVoiceService and devicePcmSpeaker already take this same instance. So it is
            // the correct render reference on the HA-Assist lane too.
            aecControllerProvider = { cascadeAecCtl },
            ttsOverride = { degradedTtsProvider() },
            onPlayConfirmationTone = { onPlayConfirmationTone?.invoke() },
            deviceEngineId = { runCatching { ttsProvider()?.defaultEngine }.getOrNull() },
        )
    }

    /**
     * WS-D.1: the TTS provider to use INSTEAD of the configured one while out of credits, or
     * null to honour the setting.
     *
     * 🔴 **Two sources, because they cover different lanes.** The cascade's [DegradedVoiceMode]
     * plan is owned by `VoicePipelineCoordinator` — which does not exist on the HA-Assist lane,
     * so neither does the degradation planner. Routing HA-Assist through [NativeResponseTts]
     * with only the coordinator's override would therefore have let that lane select a BILLED
     * cloud voice at $0: a billing hole opened by fixing a TTS bug. Where there is no planner,
     * fall back to the engine that is always free and always present — the device one.
     * Cascade behaviour is unchanged (the floor is reachable only when the coordinator is null).
     */
    private fun degradedTtsProvider(): String? {
        voicePipelineCoordinator?.let { return it.degradedTtsProvider() }
        if (CreditStateHolder.spendable) return null   // fail OPEN: unknown/healthy → honour the setting
        Log.w(TAG, "DROP: out of credits and no degradation planner on this lane — " +
            "forcing the free device voice instead of the configured ${halitePrefs.voice.ttsProvider}")
        return com.dashieapp.Dashie.halite.preferences.VoicePreferences.TTS_ANDROID_VOICE
    }

    private fun speakNativeResponse(text: String, brainVoiceId: String?, brainVoiceProvider: String?, sessionId: String?, onDone: () -> Unit) {
        nativeResponseTts.speak(
            text = text,
            brainVoiceId = brainVoiceId,
            brainVoiceProvider = brainVoiceProvider,
            sessionId = sessionId,
            onSpeechEnd = { onTtsSpeechEnd() },   // cascade owns turn-end here — see NativeResponseTts
            onDone = onDone,
        )
    }

    /**
     * Notify the active voice service that TTS has completed.
     */
    private fun notifyTtsComplete() {
        haVoiceService?.onLocalTtsComplete()
        // VoicePipelineCoordinator doesn't need TTS completion notification -
        // it handles timing internally via postDelayed in processTranscriptWithOverlay
    }

    /**
     * Reinitialize voice after pipeline mode change.
     * Shuts down existing voice service and initializes the new one based on current preferences.
     * Keeps wake word detection and audio capture running - only switches the voice service.
     */
    fun reinitialize() {
        // No-op if voice isn't currently running. initializeVoicePipeline/
        // initializeHaVoiceService dereference haliteAudioBuffer/haliteWakeWord
        // with !!, so reinitializing before voice has started would NPE. The new
        // prefs are picked up by the normal initialize() path on next startup.
        if (haliteAudioBuffer == null || haliteWakeWord == null) {
            Log.i(TAG, "🎤 Skipping voice reinitialize — voice not currently active")
            return
        }

        Log.i(TAG, "🎤 Reinitializing voice (useOverlayNlp=${halitePrefs.voice.useOverlayNlp})")

        // Shutdown current voice services only (keep wake word and audio capture running)
        haVoiceService?.shutdown()
        haVoiceService = null
        voicePipelineCoordinator?.release()
        haTtsSynthesizer?.release()
        haTtsSynthesizer = null
        voicePipelineCoordinator = null

        // Re-initialize with current pipeline mode
        val haUrl = halitePrefs.connection.haUrl
        if (haUrl.isEmpty()) {
            Log.e(TAG, "🎤 Cannot reinitialize voice - no HA URL configured")
            return
        }

        // Choose voice pathway based on useOverlayNlp setting
        if (halitePrefs.voice.useOverlayNlp) {
            initializeVoicePipeline(haUrl)
        } else {
            initializeHaVoiceService(haUrl)
        }

        // FB27: the pipeline (and thus billability) may have changed — e.g. switching to Voice
        // Assistant. The HA-voice-service path above doesn't set this, so refresh here to hide
        // the credit UI (badge/pill/card) when the new pipeline is no longer billable.
        CreditStateHolder.setBillable(halitePrefs.voice.isBillableVoice)
        paywall?.updateLowCreditPill()

        Log.i(TAG, "🎤 Voice reinitialized - now using ${if (halitePrefs.voice.useOverlayNlp) "VoicePipelineCoordinator (AI Routing)" else "HaVoiceService (HA Assist)"}")
    }

    /**
     * Disable voice when toggled off or trial expires.
     * Stops wake word detection and audio capture to save CPU/battery.
     * Components are fully cleaned up and will be recreated when re-enabled.
     */
    fun disable() {
        // Stop and close wake word detection
        haliteWakeWord?.stop()
        haliteWakeWord?.close()
        haliteWakeWord = null

        // Stop and shutdown audio capture
        haliteAudioCapture?.stop()
        haliteAudioCapture?.shutdown()
        haliteAudioCapture = null
        cascadeAecCtl?.release()
        cascadeAecCtl = null

        // Clear audio buffer
        haliteAudioBuffer = null

        // Clear sample collection
        haliteSampleCollector = null
        haliteSampleUploader = null

        // Shutdown voice services
        haVoiceService?.shutdown()
        haVoiceService = null

        voicePipelineCoordinator?.release()

        haTtsSynthesizer?.release()

        haTtsSynthesizer = null
        voicePipelineCoordinator = null

        Log.i(TAG, "🎤 Voice disabled - all voice components stopped and cleaned up")
    }

    /**
     * Shutdown Halite voice control completely.
     * Called when Activity is being destroyed.
     */
    /**
     * Construct and start the capability lease: renewal loop + the #68 revocation nudge.
     *
     * Idempotent — a re-init (sleep → wake) must not stack a second loop, so an existing one is
     * stopped first. The lease OBJECT is rebuilt with it: its state is per-session and a stale
     * expiry carried across a teardown would be a lease nobody re-earned.
     *
     * 🔑 AI-routing lane ONLY, and DELIBERATELY so rather than where the call happened to land —
     * ruling, attribution argument and the one known gap on [CapabilityLease] ("Which lane this
     * governs"). The HA-Assist branch announces its non-start via `LeaseMarkers.markNotStarted`.
     */
    private fun startCapabilityLease() {
        leaseRenewal?.stop()
        haEvents?.stop()

        // 🔴 A device that OWNS its session borrows nothing, so the household lease must not
        // govern it — see [LeaseGovernance] for the bug and the predicate. NOT_APPLICABLE renders
        // no row: "AI sharing is off" is FALSE here, not merely unhelpful.
        if (!LeaseGovernance.governs(
                isLinked = halitePrefs.account.isLinked,
                kioskProvisionedSession = halitePrefs.account.kioskProvisionedSession)) {
            LeaseMarkers.fallbackEmit = { line -> Log.i(LeaseMarkers.TAG, line) }
            LeaseMarkers.markNotStarted(LeaseGovernance.OWN_SESSION_REASON)
            LeaseStateHolder.onNotApplicable()
            return
        }

        // 🔴 INSTALL THE MARKER SINK FIRST. LeaseMarkers.emit defaults to a NO-OP so the pure
        // core stays Android-free and unit tests can capture; production has to install a real
        // logger or every lease transition is invisible — which is precisely the silent
        // capability disappearance standing rule 2 forbids, and would make T's L1/L2/L4 rows
        // unprovable. Missed on the first cut and caught only by a device run: the unit tests
        // install their OWN sink, so no test can ever detect a missing production one.
        LeaseMarkers.emit = { line -> Log.i(LeaseMarkers.TAG, line) }

        val lease = com.dashieapp.Dashie.halite.voice.lease.CapabilityLease(
            onDestruct = { cause ->
                // DESTROYED = downgrade, never outage. Voice keeps working on HA/local engines;
                // it stops using the household's metered capability. The marker is emitted by the
                // lease itself, so a failure to degrade here is still visible in the log.
                val degraded = DegradedVoiceSink.enter("lease_lapsed:$cause")
                if (!degraded) {
                    Log.w(TAG, "DROP: lease lapsed ($cause) but no free engine is available on " +
                        "this device — voice cannot continue on the fallback. This is the honest " +
                        "outcome, not a retryable error; the device needs a local/HA engine set up.")
                }
            },
            onRestore = { DegradedVoiceSink.clear(full = true) },
        )

        val renewal = com.dashieapp.Dashie.halite.voice.lease.LeaseRenewalService(
            lease = lease,
            // Live reads: HA URL can change and the token rotates ~30 min. Capturing either would
            // fail every renewal after the first cycle, which this protocol correctly reads as
            // UNKNOWN — so the device would self-destruct on a bug that looks like an unreachable
            // add-on.
            haUrlProvider = { halitePrefs.connection.getHaOrigin().orEmpty() },
            haTokenProvider = { halitePrefs.connection.haAccessToken },
            // SAME identity the brain and user_devices key on — see VoicePipelineCoordinator's
            // note on endpointId. Anything else and the add-on cannot match this device.
            endpointIdProvider = { com.dashieapp.Dashie.util.StableDeviceId.read(context) },
        )

        val events = com.dashieapp.Dashie.halite.ha.HaEventSubscriber(
            haUrlProvider = { halitePrefs.connection.getHaOrigin().orEmpty() },
            haTokenProvider = { halitePrefs.connection.haAccessToken },
        )
        com.dashieapp.Dashie.halite.voice.lease.LeaseNudgeListener(
            renewNow = { reason -> renewal.renewNow(reason) },
            endpointIdProvider = { com.dashieapp.Dashie.util.StableDeviceId.read(context) },
        ).attach(events)

        capabilityLease = lease
        leaseRenewal = renewal
        haEvents = events

        renewal.start(scope)
        events.start()
        Log.i(TAG, "🔑 capability lease started (renewal loop + revocation nudge)")
    }

    fun shutdown() {
        // Stop the lease loop BEFORE the scope dies, so it unsubscribes cleanly rather than
        // being cancelled mid-request. Stopping is not revoking: the lease state is untouched,
        // so a re-init resumes with whatever capability it still legitimately holds.
        leaseRenewal?.stop()
        haEvents?.stop()

        // Cancel any pending coroutines
        scope.cancel()

        haVoiceService?.shutdown()
        haVoiceService = null

        voicePipelineCoordinator?.release()

        haTtsSynthesizer?.release()

        haTtsSynthesizer = null
        voicePipelineCoordinator = null

        haliteWakeWord?.close()
        haliteWakeWord = null

        haliteAudioCapture?.shutdown()
        haliteAudioCapture = null
        cascadeAecCtl?.release()
        cascadeAecCtl = null

        haliteAudioBuffer = null

        haliteSampleCollector = null
        haliteSampleUploader = null

        // Detach but DON'T null: re-init after a voice disable→enable toggle never re-runs
        // setupVoiceIndicator, and the content view is unchanged, so the bindings stay valid
        // (on a real Activity destroy the whole registry is replaced and this is GC'd anyway).
        voiceIndicatorController?.detach()

        Log.i(TAG, "🎤 Halite voice control shutdown")
        onVoiceShutdown?.invoke()
    }

    /**
     * Check if voice is currently initialized and running.
     */
    fun isInitialized(): Boolean = haVoiceService != null || voicePipelineCoordinator != null

    /**
     * Check if wake word detection is currently active (listening for wake word).
     * This impacts CPU usage significantly.
     */
    fun isWakeWordActive(): Boolean = haliteWakeWord?.isDetecting() ?: false

    /**
     * Check if a voice interaction is currently in progress (STT/TTS active).
     * This impacts CPU usage significantly.
     */
    fun isVoiceInteractionActive(): Boolean {
        // Check HaVoiceService
        haVoiceService?.getState()?.let { state ->
            if (state != HaVoiceService.VoiceState.IDLE && state != HaVoiceService.VoiceState.ERROR) {
                return true
            }
        }
        // Check VoicePipelineCoordinator
        voicePipelineCoordinator?.getState()?.let { state ->
            if (state != VoicePipelineCoordinator.PipelineState.IDLE && state != VoicePipelineCoordinator.PipelineState.ERROR) {
                return true
            }
        }
        return false
    }

    /**
     * Get memory status for wake word detector.
     * Used by MemoryMonitor for component-specific diagnostics.
     */
    fun getWakeWordMemoryStatus(): String {
        return haliteWakeWord?.getMemoryStatus() ?: "not_initialized"
    }

    /**
     * Get VoiceOverlayBridge for wiring to DashieJSBridge.
     * Returns null if not using overlay NLP mode.
     */
    fun getVoiceOverlayBridge(): VoiceOverlayBridge? {
        return voicePipelineCoordinator?.getOverlayBridge()
    }

    /** 🧪 Headless full-pipeline test injection, like a spoken turn (see VPC.injectTranscript). */
    fun injectTranscript(text: String, announcement: Boolean = false): Boolean =
        voicePipelineCoordinator?.injectTranscript(text, announcement) != null

    /**
     * Run [block] once the user isn't mid-conversation with Dashie (see the coordinator's
     * conversation-idle gate) — used so a scheduled action never barges into a live chat.
     *
     * Returns false ONLY when the pipeline isn't wired yet (post-restart), which the caller
     * must treat as "not ready, retry". A queued task returns TRUE: it's accepted, not
     * dropped, so the caller must NOT burn its retry budget waiting for it — the difference
     * between "broken" and "busy".
     */
    fun runWhenIdle(tag: String, block: () -> Unit): Boolean {
        val vpc = voicePipelineCoordinator ?: return false
        vpc.runWhenIdle(tag, block)
        return true
    }

    // ============================================
    // Local Command Interception (HA Assist mode)
    // Intercepts voice transcripts before HA intent processing
    // and routes timer/music/volume commands locally
    // ============================================

    /**
     * Check if a voice transcript is a local command that should be handled locally.
     * Returns TTS response text if intercepted, null to let HA handle it.
     * Uses KotlinIntentPatterns to classify timer, music, and volume commands.
     */
    private fun checkLocalCommandInterception(transcript: String): String? {
        val musicPlaying = isMusicPlayingProvider?.invoke() ?: false
        val alarmPlaying = isAlarmPlayingProvider?.invoke() ?: false
        val timerRemaining = timerRemainingProvider?.invoke()

        Log.d(TAG, "🎤 Intercept check: transcript='$transcript', music=$musicPlaying, alarm=$alarmPlaying, timerRemaining=$timerRemaining")

        val result = KotlinIntentPatterns.classify(
            transcript, musicPlaying, alarmPlaying, timerRemaining
        )
        if (result == null) {
            Log.d(TAG, "🎤 No local match for: '$transcript' → sending to HA")
            return null
        }

        Log.i(TAG, "🎤 Local command intercepted: ${result.category}/${result.command}")

        when (result.category) {
            "timer" -> dispatchTimerCommand(result.command, result.params)
            "media" -> dispatchMediaCommand(result.command, result.params)
            "volume" -> dispatchVolumeCommand(result.command, result.params)
            "playback_mode" -> onPlaybackModeCommand?.invoke(result.command)
            "speaker" -> dispatchSpeakerCommand(result.command, result.params)
            "videofeed" -> onVideoFeedCommand?.invoke(result.command, result.params)
            "schedule" -> onScheduleCommand?.invoke(result.command, result.params)
            "app" -> onOpenAppCommand?.invoke(result.command, result.params)
        }

        lastTranscriptWasMusicCommand = result.category in listOf("media", "playback_mode", "speaker")
        return result.ttsResponse
    }

    /** Dispatch a timer command to the overlay iframe via callback. */
    private fun dispatchTimerCommand(command: String, params: Map<String, Any>) {
        // Timer query doesn't need dispatch - TTS response is self-contained
        if (command == "query_time") return
        onTimerCommand?.invoke(command, params)
    }

    /** Dispatch a media command to the HA WebView's injected JS. */
    private fun dispatchMediaCommand(command: String, params: Map<String, Any>) {
        val apiCallback = onMusicCommand

        when (command) {
            // Playback controls: prefer MA API (fast, no HA iframe dependency)
            "play" -> {
                onMusicPlayInitiated?.invoke()
                if (apiCallback != null) apiCallback("play", "{}")
                else dispatchMusicEvent("music-player-play")
            }
            "pause" -> {
                if (apiCallback != null) apiCallback("pause", "{}")
                else dispatchMusicEvent("music-player-pause")
            }
            "next" -> {
                if (apiCallback != null) apiCallback("next", "{}")
                else dispatchMusicEvent("music-player-next")
            }
            "previous" -> {
                if (apiCallback != null) apiCallback("previous", "{}")
                else dispatchMusicEvent("music-player-previous")
            }
            "stop" -> {
                if (apiCallback != null) apiCallback("stop", "{}")
                else dispatchMusicEvent("music-player-stop")
            }
            // play_search: ALWAYS use HA iframe path — HA's music_assistant.play_media
            // service handles search strings, while the raw MA REST API does not.
            "play_search" -> {
                onMusicPlayInitiated?.invoke()
                val targetSpeaker = params["targetSpeaker"] as? String
                if (targetSpeaker != null) {
                    onPlayOnSpeaker?.invoke(
                        params["mediaId"] as? String ?: "",
                        targetSpeaker,
                        params
                    )
                } else {
                    val detail = JSONObject().apply {
                        params["mediaId"]?.let { put("mediaId", it) }
                        params["artist"]?.let { put("artist", it) }
                        params["mediaType"]?.let { put("mediaType", it) }
                    }.toString()
                    dispatchMusicEventWithDetail("music-player-play-media", detail)
                }
            }
        }
    }

    /** Dispatch a speaker mute/unmute command via callback. */
    private fun dispatchSpeakerCommand(command: String, params: Map<String, Any>) {
        val speakerName = params["targetSpeaker"] as? String ?: return
        when (command) {
            "mute" -> onSpeakerMuteCommand?.invoke(speakerName, true)
            "unmute" -> onSpeakerMuteCommand?.invoke(speakerName, false)
        }
    }

    /** Dispatch a volume command via callbacks. */
    private fun dispatchVolumeCommand(command: String, params: Map<String, Any>) {
        when (command) {
            "volume_up" -> onVolumeUp?.invoke(1)
            "volume_down" -> onVolumeDown?.invoke(1)
            "volume_set" -> {
                val level = (params["level"] as? Int) ?: return
                onSetVolume?.invoke(level)
            }
        }
    }

    /** Dispatch a CustomEvent to the HA WebView for the injected music JS to handle. */
    private fun dispatchMusicEvent(eventName: String) {
        webView.post {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('$eventName'));",
                null
            )
        }
    }

    /** Dispatch a CustomEvent with detail payload to the HA WebView. */
    private fun dispatchMusicEventWithDetail(eventName: String, detail: String) {
        webView.post {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('$eventName', { detail: $detail }));",
                null
            )
        }
    }
}

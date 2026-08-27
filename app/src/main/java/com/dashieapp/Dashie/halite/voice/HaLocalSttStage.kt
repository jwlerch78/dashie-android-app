package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.os.Handler
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.voice.stt.SttListener
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType
import com.dashieapp.Dashie.util.StableDeviceId

/**
 * The STT stage for HA Voice Assist mode when the transcript should NOT come from HA's Assist
 * pipeline — i.e. the user picked an on-device or engine-direct engine (sherpa Moonshine, HA
 * Whisper via `/api/stt/{engine}`, own-box Whisper), or the pipeline can't transcribe at all.
 *
 * Why this exists (2026-07-29): `ha_assist` always ran `start_stage=stt`, so a tablet set to
 * `stt_provider=sherpa_moonshine_tiny` still streamed audio for HA to transcribe — the local choice
 * was silently discarded, and when HA's preferred pipeline had no `stt_engine` every turn died with
 * "the pipeline does not support speech-to-text". With this stage Dashie transcribes first and hands
 * HA text, so HA still owns intent, execution and the response (the HA_VOICE_PATHWAYS invariant
 * that HA-Assist mode is not unified with the cascade holds — only the STT stage moved).
 *
 * Reuses the cascade's provider stack verbatim ([SttProviderFactory]) and the shared read loop
 * ([SharedBufferAudioPump]); the only thing here is the one-shot session lifecycle.
 */
class HaLocalSttStage(
    private val context: Context,
    private val halitePrefs: HalitePreferences,
    private val handler: Handler,
    /**
     * Mic ownership swap for `AndroidSpeechRecognizerProvider`, supplied by the controller —
     * without it "On-Device (Built-in)" cannot register in this mode (see [prepare]).
     *
     * ⚠️ **HA-Assist's handoff MUST restore CAPTURE ONLY.** The cascade's also re-arms the wake
     * detector, which is right there because STT ends the turn; here the turn CONTINUES (text →
     * HA → intent → TTS), and [HaVoiceService] already owns re-arm on all three of its exits. A
     * cascade-shaped restore would make the detector live during HA's spoken reply — self-hearing,
     * the class the cascade guards with its own `!inConversation` check.
     */
    private val micHandoff: com.dashieapp.Dashie.halite.voice.stt.AndroidSpeechRecognizerProvider.MicHandoff? = null,
) {

    companion object {
        private const val TAG = "HaLocalStt"

        /** Matches HaVoiceService's cap for one utterance. */
        private const val MAX_UTTERANCE_MS = 10_000L

        /** Backup end-of-speech silence. Same 1.5s HaVoiceService's own VAD uses, so the
         *  end-of-speech feel in HA Voice Assist mode is unchanged. Buffered providers (HA
         *  engine-direct, own-box Whisper) depend on this; sherpa endpoints itself. */
        private const val SILENCE_TIMEOUT_MS = 1500L
    }

    sealed interface Result {
        data class Text(val text: String) : Result
        object NoSpeech : Result
        data class Failed(val error: String) : Result
    }

    private var sttManager: SttProviderManager? = null
    private val pump = SharedBufferAudioPump(handler, TAG)
    private var vad: VoiceActivityDetector? = null

    /** Held so the prewarmed STT session token stays alive (see SttProviderFactory.Providers). */
    private var credentialProvider: com.dashieapp.Dashie.halite.voice.stt.SttCredentialProvider? = null

    private var listening = false
    private var delivered = false
    private var onResult: ((Result) -> Unit)? = null

    /** Provider chain resolved at [prepare] time, minus HA's Assist pipeline. */
    private var localChain: List<ProviderType> = emptyList()

    /**
     * Build the provider stack. Suspending because [SttProviderFactory.createProviders] is —
     * call it from the controller's init scope, as the coordinator does.
     */
    suspend fun prepare() {
        val providers = SttProviderFactory.createProviders(
            halitePrefs,
            StableDeviceId.read(context),
            context,
            // 🔴 2026-08-24: this used to pass null, on the reasoning that SpeechRecognizer would
            // fight the shared capture. The premise was right and the conclusion wrong — mic
            // contention is exactly what MicHandoff was built for, and the cascade had solved it
            // next door for a month. The cost of the null was NOT a missing feature: the chain
            // still RANKED ANDROID_NATIVE first (resolvePriority honours the user's pick), so a
            // user who chose "On-Device (Built-in)" silently got HA's faster_whisper — audio off
            // the tablet — while HaVoiceService logged `android_voice`. Device-observed 2/2,
            // A rung that is ranked must be registered, or not ranked.
            micHandoff = micHandoff,
        )
        sttManager = providers.manager
        credentialProvider = providers.credentialProvider

        val priority = SttProviderFactory.resolvePriority(halitePrefs.voice)
        providers.manager.setProviderPriority(priority.order)
        if (priority.coercedFromCloud) {
            Log.w(TAG, "DROP: cloud STT selected but HA Voice Assist is a no-cloud mode — " +
                "using ${priority.order.firstOrNull()} instead")
        }
        // HA_ASSIST is excluded: reaching it means "let the pipeline transcribe", which is the
        // audio path, not this stage.
        localChain = priority.order.filter { it != ProviderType.HA_ASSIST }

        vad = VoiceActivityDetector().apply {
            setSilenceTimeout(SILENCE_TIMEOUT_MS)
            setMinSpeechDuration(300L)
            onEndOfSpeech = { handleEndOfSpeech() }
        }
        Log.i(TAG, "Prepared — chain=${priority.order}, local rungs=$localChain " +
            "(${priority.description})")
    }

    /**
     * Can this device actually transcribe without HA's pipeline right now? Drives the stage
     * decision — claiming a local stage we can't run would send HA an empty transcript.
     */
    fun localSttAvailable(): Boolean {
        val manager = sttManager ?: return false
        val usable = localChain.filter { manager.getProvider(it)?.isAvailable() == true }
        if (usable.isEmpty() && localChain.isNotEmpty()) {
            Log.w(TAG, "DROP: no local STT rung is available (chain=$localChain) — " +
                "falling back to the HA Assist pipeline for STT")
        }
        // 🔴 THE GAP THAT LET THE 08-24 DEFECT RUN SILENTLY. The warn above fires only when
        // EVERYTHING is dead — but the case that actually happens is "the rung the user PICKED is
        // missing while a fallback quietly covers for it", which is a substitution the user never
        // agreed to and, until now, produced no output at all. Report the SUBSTITUTION, not just
        // the total failure: a silent downgrade is what Standing Rule 2 exists to prevent.
        val picked = localChain.firstOrNull()
        if (picked != null && usable.isNotEmpty() && usable.first() != picked) {
            val registered = manager.getProvider(picked) != null
            Log.w(TAG, "DROP: the selected STT rung $picked is " +
                (if (registered) "REGISTERED BUT UNAVAILABLE" else "NOT REGISTERED on this device") +
                " — substituting ${usable.first()} (chain=$localChain). " +
                "The user picked $picked; this turn does NOT run it.")
        }
        return usable.isNotEmpty()
    }

    /** The engine that actually transcribed, for logging/diagnostics. */
    val activeProviderId: String? get() = sttManager?.activeProviderId

    /**
     * The rung that WILL run the next turn (first available in the chain), or null if none is.
     *
     * Exists so callers can report the engine they are about to use instead of the user's
     * preference — `HaVoiceService` logged `"STT stage = local (android_voice)"` off the raw pref
     * while `ha_engine` did the work, which made the one line a debugger would trust false.
     */
    val resolvedLead: ProviderType? get() = sttManager?.getPrimaryProviderType()

    /**
     * Transcribe one utterance from [buffer], starting just past [wakeWordBufferPosition].
     * [onResult] fires exactly once, on the handler thread.
     */
    fun transcribe(
        buffer: SharedAudioBuffer,
        wakeWordBufferPosition: Long,
        onResult: (Result) -> Unit,
    ) {
        val manager = sttManager
        if (manager == null) {
            onResult(Result.Failed("Local STT not initialized"))
            return
        }

        cancel()
        this.onResult = onResult
        delivered = false
        vad?.reset()

        // Same tail skip the cascade uses for this engine — shared so the two can't drift.
        // 🔴 Keyed on the RESOLVED PROVIDER, not `halitePrefs.voice.sttProvider`. Using the pref
        // meant that whenever the chain substituted (which in this mode it did on every turn —
        // see [localSttAvailable]'s second DROP), the skip described an engine that was not
        // running: `android_voice` → 1600 (net 0 ms) handed HA's whole-clip faster_whisper the
        // retained wake tail, the documented hallucination feed. See SttProviderFactory.
        val from = wakeWordBufferPosition +
            SttProviderFactory.wakeTailSkipSamples(manager.getPrimaryProviderType())

        val started = manager.startSession(object : SttListener {
            override fun onSessionStarted() {
                Log.d(TAG, "STT session started (provider=${manager.activeProviderId})")
                listening = true
                // 🔴 A mic-owning provider (SpeechRecognizer) must NOT be pumped. Not merely
                // because its streamAudio is a no-op — because the pump also drives OUR VAD, and
                // this stage's VAD has no "wait for a transcript" guard (the cascade's
                // onVadEndOfSpeech does). The provider's own 800 ms lost-release fallback can
                // start the recognizer while Dashie's capture is STILL RUNNING; the VAD would then
                // see live audio, count 1.5 s of silence after the wake tail and call
                // endAudioStream() → stopListening() MID-COMMAND. The recognizer owns its own
                // endpointing, so there is nothing for us to decide here.
                if (manager.activeOwnsMic) {
                    Log.i(TAG, "Provider ${manager.activeProviderId} owns the mic — " +
                        "no audio pump, no VAD (it endpoints itself)")
                    return
                }
                pump.start(
                    buffer = buffer,
                    fromPosition = from,
                    maxDurationMs = MAX_UTTERANCE_MS,
                    keepRunning = { listening },
                    onMaxDuration = {
                        Log.d(TAG, "Max utterance duration — closing the audio stream")
                        handleEndOfSpeech()
                    },
                ) { pcm ->
                    vad?.processAudio(pcm)
                    manager.streamAudio(pcm)
                }
            }

            override fun onInterimResult(text: String) {
                Log.d(TAG, "Interim: $text")
            }

            override fun onFinalResult(text: String) {
                Log.i(TAG, "Final transcript (${manager.activeProviderId}): $text")
                stopCapture()
                deliver(if (text.isBlank()) Result.NoSpeech else Result.Text(text))
            }

            override fun onNoSpeechDetected() {
                Log.d(TAG, "No speech detected")
                stopCapture()
                deliver(Result.NoSpeech)
            }

            override fun onError(error: String, isRecoverable: Boolean) {
                Log.e(TAG, "STT error: $error (recoverable=$isRecoverable)")
                stopCapture()
                deliver(Result.Failed(error))
            }

            override fun onSessionEnded() {
                Log.d(TAG, "STT session ended")
            }
        })

        if (!started) {
            stopCapture()
            deliver(Result.Failed("Failed to start local STT"))
        }
    }

    /** Abandon any in-flight utterance without delivering a result. */
    fun cancel() {
        stopCapture()
        try { sttManager?.cancelSession() } catch (_: Exception) {}
        onResult = null
    }

    fun release() {
        cancel()
        sttManager?.release()
        sttManager = null
        credentialProvider = null
        vad = null
    }

    private fun handleEndOfSpeech() {
        if (!listening) return
        listening = false
        pump.stop()
        sttManager?.endAudioStream()
    }

    private fun stopCapture() {
        listening = false
        pump.stop()
    }

    private fun deliver(result: Result) {
        if (delivered) return
        delivered = true
        val cb = onResult
        onResult = null
        handler.post { cb?.invoke(result) }
    }
}

package com.dashieapp.Dashie.halite.voice.stt

/**
 * Speech-to-Text Provider Interface
 *
 * Abstraction for different STT backends:
 * - HaAssistSttProvider: Uses Home Assistant's Assist pipeline (local Whisper)
 * - DeepgramSttProvider: Uses Deepgram's cloud API
 *
 * Part of the multi-pathway voice architecture for Dashie Kiosk.
 * STT providers convert audio to text; the overlay's NLP handles intent processing.
 */
interface SttProvider {
    /**
     * Provider identifier for logging and configuration
     */
    val providerId: String

    /**
     * Human-readable name for UI display
     */
    val displayName: String

    /**
     * Wave-2 timing: provider-measured transcription latency (end-of-audio → final
     * transcript) for the LAST completed session, or null for streaming providers.
     * Buffered/POST providers (HA Whisper) end audio on their OWN VAD and fire
     * onFinalResult before the coordinator's backup VAD, so the coordinator's audio-end
     * anchor races to 0 — those set this precisely instead. Streaming providers (Deepgram)
     * leave it null and let the coordinator time the stage.
     */
    val lastTranscribeMs: Long? get() = null

    /**
     * This provider opens the MICROPHONE ITSELF and cannot be fed [streamAudio].
     *
     * 🔴 THE SEAM (2026-08-24). Every consumer that pumps shared-capture PCM must skip the pump
     * for such a provider — not merely because the bytes are ignored, but because the pump also
     * drives the caller's VAD, and a VAD fed live audio while the recognizer holds the mic will
     * call [endAudioStream] mid-command. Before this property that fact was a HAND-MIRROR: a
     * comment in `SttProviderFactory.WHOLE_CLIP_DECODERS` said "`android_voice` … would be inert
     * anyway", and the two shared-buffer consumers (VoicePipelineCoordinator's streaming loop and
     * HaLocalSttStage's pump) each had to know it independently. Neither did.
     *
     * It also decides whether the provider needs a
     * [AndroidSpeechRecognizerProvider.MicHandoff] to be registered at all.
     */
    val ownsMic: Boolean get() = false

    /**
     * Whether this provider is currently available/configured
     */
    fun isAvailable(): Boolean

    /**
     * Initialize the provider with necessary configuration.
     * Called once before first use.
     *
     * @param config Provider-specific configuration
     * @return true if initialization successful
     */
    suspend fun initialize(config: SttConfig): Boolean

    /**
     * Optional: do the expensive one-time setup NOW, off the first turn.
     *
     * Called by [SttProviderManager.setProviderPriority] on the provider that will actually run —
     * never on the whole registry, because a warm-up that loads every registered engine would cost
     * RAM on exactly the floor devices this feature targets (Fire, Mio).
     *
     * MUST be idempotent, non-blocking, and safe to call before/without a session. Default no-op:
     * a network provider has nothing to warm.
     */
    fun prewarm() {}

    /**
     * Start a new STT session.
     * Call this when wake word is detected, before streaming audio.
     *
     * @param listener Callback for STT events
     */
    fun startSession(listener: SttListener)

    /**
     * Stream audio data to the STT provider.
     * Audio format: 16kHz, mono, 16-bit PCM
     *
     * @param audioData PCM audio bytes
     */
    fun streamAudio(audioData: ByteArray)

    /**
     * Signal end of audio input.
     * Provider should finalize transcription.
     */
    fun endAudioStream()

    /**
     * Cancel the current session without waiting for final result.
     */
    fun cancelSession()

    /**
     * Release resources. Call when provider is no longer needed.
     */
    fun release()
}

/**
 * Configuration for STT providers
 */
data class SttConfig(
    // Home Assistant configuration (for HaAssistSttProvider)
    val haUrl: String? = null,
    val haToken: String? = null,
    // LIVE token read, resolved at session/request time — PREFER over haToken. The HA
    // access token rotates every ~30 min (JS refresh → ConnectionPreferences); a
    // provider that snapshots config.haToken at init sends a dead token after the
    // first rotation ("Invalid access token" on every HA STT call ~30 min after app
    // start, 2026-07-13). Same pattern as Deepgram's credentialProvider.
    val haTokenProvider: (() -> String)? = null,
    val haPipelineId: String? = null,

    // Engine-direct HA STT (HaSttEngineDirectProvider, ha_engine): which HA STT
    // engine to POST to, e.g. "stt.faster_whisper".
    val haSttEngineId: String? = null,
    // Own-box OpenAI-compatible Whisper (LocalWhisperSttProvider, local_stt_url).
    val localSttUrl: String? = null,

    // Deepgram configuration is handled by edge function - no config needed on device
    // The edge function uses nova-3 model and handles all Deepgram settings

    // Common settings
    val sampleRate: Int = 16000,
    val enableInterimResults: Boolean = true,
    val endOfSpeechTimeoutMs: Long = 1500
)

/**
 * Listener for STT events
 */
interface SttListener {
    /**
     * Called when STT session has started and is ready for audio
     */
    fun onSessionStarted()

    /**
     * Called with interim (partial) transcription results.
     * Not all providers support this.
     *
     * @param text Partial transcription
     */
    fun onInterimResult(text: String)

    /**
     * Called when final transcription is ready.
     * This is the definitive result - no more updates will come.
     *
     * @param text Final transcription
     */
    fun onFinalResult(text: String)

    /**
     * Called when no speech was detected (silence or noise only)
     */
    fun onNoSpeechDetected()

    /**
     * Called when an error occurs
     *
     * @param error Error description
     * @param isRecoverable true if session can be retried
     */
    fun onError(error: String, isRecoverable: Boolean)

    /**
     * Called when the session ends (after final result or error)
     */
    fun onSessionEnded()
}

/**
 * Result from an STT session
 */
sealed class SttResult {
    data class Success(val transcript: String) : SttResult()
    data class NoSpeech(val reason: String = "No speech detected") : SttResult()
    data class Error(val message: String, val isRecoverable: Boolean = true) : SttResult()
    object Cancelled : SttResult()
}

package com.dashieapp.Dashie.halite.voice.stt

import android.util.Log

/**
 * STT Provider Manager
 *
 * Manages available STT providers and coordinates provider selection.
 * Supports fallback: if primary provider fails, can try secondary.
 *
 * Provider priority (configurable):
 * 1. Deepgram (cloud) - fastest, most accurate
 * 2. HA Assist (local Whisper) - works offline, requires HA
 *
 * Part of the multi-pathway voice architecture for Dashie Kiosk.
 */
class SttProviderManager {
    companion object {
        private const val TAG = "SttProviderManager"
    }

    /**
     * Available STT provider types
     */
    enum class ProviderType {
        HA_ASSIST,      // Home Assistant Assist pipeline (local Whisper)
        DEEPGRAM,       // Deepgram cloud API
        HA_ENGINE,      // HA engine-direct STT (Whisper etc.), no Assist pipeline
        LOCAL_WHISPER,  // Own-box OpenAI-compatible Whisper (local_stt_url)
        // Android's built-in SpeechRecognizer. ⚠️ Owns its OWN microphone (it cannot be fed
        // streamAudio like the others), so it needs the coordinator's mic handoff — see
        // AndroidSpeechRecognizerProvider. Absent on Fire OS / Lineage (no Google services).
        ANDROID_NATIVE,
        // Bundled sherpa-onnx on-device STT (dev flavors bundle engine+models via
        // src/sttEngine — SherpaMoonshineSttProvider). Shared-capture PCM like the
        // buffered providers; unavailable when the APK carries no engine.
        SHERPA_MOONSHINE_TINY,
        SHERPA_MOONSHINE_BASE
    }

    // Registered providers
    private val providers = mutableMapOf<ProviderType, SttProvider>()

    // Provider priority order (first available is used)
    private var providerPriority = listOf(ProviderType.DEEPGRAM, ProviderType.HA_ASSIST)

    // Current active session
    private var activeProvider: SttProvider? = null
    private var activeListener: SttListener? = null

    // Fallback enabled flag
    var enableFallback = true

    /** Engine id of the provider currently producing results ('deepgram', 'ha_assist',
     *  'ha_engine', 'local_stt_url'), or null outside a session. Read this at
     *  onFinalResult time — the fallback wrapper can switch providers mid-session,
     *  and timing rows must be attributed to the engine that actually delivered. */
    val activeProviderId: String?
        get() = activeProvider?.providerId

    /** Wave-2 timing: the active provider's measured transcription latency (buffered/POST
     *  engines only; null for streaming). See SttProvider.lastTranscribeMs. */
    val lastTranscribeMs: Long?
        get() = activeProvider?.lastTranscribeMs

    /** The provider running THIS session opens its own microphone, so nothing may pump
     *  shared-capture PCM at it — see [SttProvider.ownsMic]. Read inside `onSessionStarted`,
     *  where the selection has been made; false outside a session. */
    val activeOwnsMic: Boolean
        get() = activeProvider?.ownsMic == true

    /**
     * Register a provider
     *
     * @param type Provider type
     * @param provider Provider instance
     */
    fun registerProvider(type: ProviderType, provider: SttProvider) {
        providers[type] = provider
        Log.i(TAG, "Registered STT provider: ${provider.providerId}")
    }

    /**
     * Unregister a provider
     *
     * @param type Provider type to remove
     */
    fun unregisterProvider(type: ProviderType) {
        providers.remove(type)?.let { provider ->
            Log.i(TAG, "Unregistered STT provider: ${provider.providerId}")
            provider.release()
        }
    }

    /**
     * Set the priority order for providers.
     * First available provider in the list will be used.
     *
     * @param priority List of provider types in priority order
     */
    fun setProviderPriority(priority: List<ProviderType>) {
        providerPriority = priority
        Log.i(TAG, "Set provider priority: ${priority.joinToString(" > ")}")
        // Warm the provider that will actually run (measured 2026-08-21): moonshine-base
        // loaded LAZILY on first decode (4,769 ms measured on the Fire) while the silero VAD
        // loaded eagerly in initialize() — two engines in one subsystem, opposite placement. The
        // result was that the first voice command after every app start took 5.5 s vs 1.1 s warm,
        // with an identical decode rtf both turns: pure load, not warm-up. That is the first
        // impression of the headline feature.
        //
        // Here rather than in the provider's initialize(): SttProviderFactory initializes BOTH
        // sherpa providers at boot, so warming there would make tiny AND base resident (~135 MB
        // each) on the RAM-constrained devices this feature exists for. This is the first moment
        // the device knows which ONE will run — and it re-fires when the account push flips the
        // priority after boot, so the newly-selected engine gets warmed too.
        val primary = getPrimaryProviderType()
        if (primary != null) {
            providers[primary]?.prewarm()
        } else {
            Log.w(TAG, "DROP: no available provider in priority ${priority.joinToString(" > ")} — nothing to prewarm")
        }
    }

    /**
     * Get the currently configured primary provider type
     */
    fun getPrimaryProviderType(): ProviderType? {
        return providerPriority.firstOrNull { providers[it]?.isAvailable() == true }
    }

    /**
     * Get the provider for a specific type
     */
    fun getProvider(type: ProviderType): SttProvider? = providers[type]

    /**
     * Set the Deepgram `endpointing` silence threshold (ms) for subsequent sessions. The cascade
     * Dialog loop raises this (~1000ms) so a mid-thought pause in a back-and-forth doesn't finalize
     * early; commands/initial inquiries keep the snappy 400ms default. No-op if Deepgram isn't registered.
     */
    fun setEndpointingMs(ms: Int) {
        (providers[ProviderType.DEEPGRAM] as? DeepgramSttProvider)?.endpointingMs = ms
    }

    /**
     * Get all available provider types
     */
    fun getAvailableProviders(): List<ProviderType> {
        return providerPriority.filter { providers[it]?.isAvailable() == true }
    }

    /**
     * Start an STT session using the best available provider.
     *
     * @param listener Callback for STT events
     * @param preferredType Optional preferred provider type
     * @return true if session started successfully
     */
    fun startSession(listener: SttListener, preferredType: ProviderType? = null): Boolean {
        // Cancel any existing session
        cancelSession()

        // Select provider
        val provider = if (preferredType != null && providers[preferredType]?.isAvailable() == true) {
            providers[preferredType]
        } else {
            // Use priority order
            providerPriority
                .mapNotNull { providers[it] }
                .firstOrNull { it.isAvailable() }
        }

        if (provider == null) {
            Log.e(TAG, "No STT provider available")
            listener.onError("No STT provider available", isRecoverable = false)
            return false
        }

        Log.i(TAG, "Starting STT session with provider: ${provider.providerId}")

        activeProvider = provider
        activeListener = listener

        // Wrap listener to handle fallback
        val wrappedListener = if (enableFallback) {
            FallbackListener(listener, provider, this)
        } else {
            listener
        }

        provider.startSession(wrappedListener)
        return true
    }

    /**
     * Stream audio to the active provider
     *
     * @param audioData PCM audio bytes
     */
    fun streamAudio(audioData: ByteArray) {
        activeProvider?.streamAudio(audioData)
    }

    /**
     * Signal end of audio input
     */
    fun endAudioStream() {
        activeProvider?.endAudioStream()
    }

    /**
     * Cancel the current session
     */
    fun cancelSession() {
        activeProvider?.cancelSession()
        activeProvider = null
        activeListener = null
    }

    /**
     * Release all providers
     */
    fun release() {
        cancelSession()
        providers.values.forEach { it.release() }
        providers.clear()
    }

    /**
     * Listener wrapper that handles fallback to secondary provider on error
     */
    private class FallbackListener(
        private val originalListener: SttListener,
        private val primaryProvider: SttProvider,
        private val manager: SttProviderManager
    ) : SttListener {
        private var hasFinalResult = false
        private var hasTriedFallback = false

        override fun onSessionStarted() {
            originalListener.onSessionStarted()
        }

        override fun onInterimResult(text: String) {
            originalListener.onInterimResult(text)
        }

        override fun onFinalResult(text: String) {
            hasFinalResult = true
            originalListener.onFinalResult(text)
        }

        override fun onNoSpeechDetected() {
            originalListener.onNoSpeechDetected()
        }

        override fun onError(error: String, isRecoverable: Boolean) {
            // If we haven't gotten a result and haven't tried fallback yet, try secondary
            if (!hasFinalResult && !hasTriedFallback && isRecoverable) {
                hasTriedFallback = true

                // Find next available provider
                val fallbackProvider = manager.providerPriority
                    .mapNotNull { manager.providers[it] }
                    .filter { it != primaryProvider && it.isAvailable() }
                    .firstOrNull()

                if (fallbackProvider != null) {
                    Log.w(TAG, "Primary provider failed, falling back to: ${fallbackProvider.providerId}")
                    manager.activeProvider = fallbackProvider
                    fallbackProvider.startSession(this)
                    return
                }
            }

            // No fallback available or already tried
            originalListener.onError(error, isRecoverable)
        }

        override fun onSessionEnded() {
            originalListener.onSessionEnded()
        }
    }
}

package com.dashieapp.Dashie.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

// Voice components
import com.dashieapp.Dashie.voice.tts.TextToSpeechManager
import com.dashieapp.Dashie.voice.stt.SpeechRecognitionManager
import com.dashieapp.Dashie.voice.stt.DeepgramConnectionManager
import com.dashieapp.Dashie.voice.wakeword.WakeWordConfig
import com.dashieapp.Dashie.voice.wakeword.WakeWordManager
import com.dashieapp.Dashie.voice.recording.AudioRecordingManager
import com.dashieapp.Dashie.voice.recording.RecordingConfig
import com.dashieapp.Dashie.voice.recording.RecordingMode

// Audio utilities
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.audio.WebRTCVADProcessor

// Utilities
import com.dashieapp.Dashie.util.DeviceInfoHelper

/**
 * VoiceAssistantCoordinator
 *
 * Pure orchestration layer for all voice-related features in Dashie.
 * Delegates to specialized managers and provides a clean public API for MainActivity.
 *
 * RESPONSIBILITIES:
 * - Initialize and coordinate voice components
 * - Route method calls to appropriate managers
 * - Wire up callbacks between components
 * - Manage component lifecycle
 *
 * DELEGATES TO:
 * - TextToSpeechManager: Text-to-speech (TTS)
 * - SpeechRecognitionManager: On-device speech-to-text
 * - DeepgramConnectionManager: Cloud streaming STT
 * - WakeWordManager: Wake word detection orchestration (Edge Impulse with automatic volume normalization)
 * - AudioRecordingManager: Audio recording (batch/streaming/manual)
 * - AudioCaptureService: Single AudioRecord feeding SharedAudioBuffer
 * - SharedAudioBuffer: Circular buffer shared between wake word and STT
 *
 * ARCHITECTURE NOTES:
 * - This class does NO business logic - pure delegation
 * - Each manager is focused on a single responsibility
 * - Callbacks flow: Manager → Coordinator → MainActivity → WebView
 * - All components are independently testable
 *
 * UPDATED: Now uses SharedAudioBuffer for seamless wake word → STT handoff.
 * No audio gap when user says "hey dashie go to next week" quickly!
 *
 * @param context Android application context
 */
class VoiceAssistantCoordinator(private val context: Context) {

    private val TAG = "VoiceCoordinator"
    private val VERSION = "6.0.0-SHARED-BUFFER" // Seamless audio handoff

    // ========== COMPONENT MANAGERS ==========

    private val textToSpeechManager = TextToSpeechManager(context)
    private val speechRecognitionManager = SpeechRecognitionManager(context)
    private val deepgramConnectionManager = DeepgramConnectionManager()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var vadProcessor: WebRTCVADProcessor? = null
    private var wakeWordManager: WakeWordManager? = null
    private var recordingManager: AudioRecordingManager? = null

    // NEW: Shared audio buffer components
    private var sharedAudioBuffer: SharedAudioBuffer? = null
    private var audioCaptureService: AudioCaptureService? = null

    // ========== CONFIGURATION ==========

    private var wakeWordConfig = WakeWordConfig.DEFAULT
    private var useStreamingSTT = true  // Default to streaming (faster!)
    private var cachedDeviceInfo: String? = null
    private var isInitialized = false

    // ========== CALLBACKS (JavaScript Bridge Compatibility) ==========

    // TTS callbacks
    var onTTSReady: (() -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingCompleted: (() -> Unit)? = null
    var onTTSError: ((String) -> Unit)? = null

    // STT callbacks
    var onListeningStarted: (() -> Unit)? = null
    var onListeningEnded: (() -> Unit)? = null
    var onSpeechResult: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    // Wake word callbacks
    var onWakeWordDetected: ((confidence: Float) -> Unit)? = null
    var onWakeWordError: ((String) -> Unit)? = null

    // Audio callbacks
    var onAudioDataCaptured: ((ByteArray) -> Unit)? = null
    var onAudioLevel: ((Float) -> Unit)? = null
    var onHeartbeat: ((Float, Float) -> Unit)? = null

    // Live sample collection callback (beta feature for training data)
    var onLiveSampleCollected: ((wavData: ByteArray, metadata: String) -> Unit)? = null

    // ========== SHARED AUDIO BUFFER ACCESS ==========

    /**
     * Get the SharedAudioBuffer for sharing with RTSP streaming.
     * This allows RTSP to share the same microphone input as wake word detection,
     * avoiding audio HAL conflicts on devices with limited audio support (like Google TV).
     *
     * @return SharedAudioBuffer if wake word is initialized, null otherwise
     */
    fun getSharedAudioBuffer(): SharedAudioBuffer? = sharedAudioBuffer

    /**
     * Check if audio capture is currently running.
     */
    fun isAudioCaptureRunning(): Boolean = audioCaptureService?.isCapturing() == true

    // ========== INITIALIZATION ==========

    init {
        Log.e(TAG, "========================================")
        Log.e(TAG, "🎤 VoiceAssistantCoordinator v$VERSION")
        Log.e(TAG, "========================================")
        logDeviceInfo()

        // WebRTC VAD DISABLED - using Deepgram's cloud VAD for silence detection
        Log.i(TAG, "ℹ️ Using Deepgram cloud VAD for silence detection (WebRTC VAD disabled)")
    }

    /**
     * Initialize Text-to-Speech
     */
    fun initializeTTS() {
        Log.d(TAG, "Initializing TTS...")

        textToSpeechManager.onReady = {
            isInitialized = true
            Log.d(TAG, "✓ TTS ready")
            onTTSReady?.invoke()
        }

        textToSpeechManager.onError = { error ->
            Log.e(TAG, "TTS error: $error")
            onTTSError?.invoke(error)
        }

        textToSpeechManager.onSpeakingStarted = {
            Log.d(TAG, "TTS started")
            onSpeakingStarted?.invoke()
        }

        textToSpeechManager.onSpeakingCompleted = {
            Log.d(TAG, "TTS completed")
            onSpeakingCompleted?.invoke()
        }

        textToSpeechManager.initialize()
    }

    /**
     * Initialize Speech Recognition (on-device STT)
     */
    fun initializeSpeechRecognition() {
        speechRecognitionManager.onListeningStarted = {
            onListeningStarted?.invoke()
        }

        speechRecognitionManager.onListeningEnded = {
            onListeningEnded?.invoke()
        }

        speechRecognitionManager.onSpeechResult = { result ->
            onSpeechResult?.invoke(result)

            // Restart wake word after getting result
            if (isWakeWordActive()) {
                handler.postDelayed({ startWakeWordDetection() }, 500)
            }
        }

        speechRecognitionManager.onSpeechError = { error ->
            onSpeechError?.invoke(error)

            // Restart wake word after error
            if (isWakeWordActive()) {
                handler.postDelayed({ startWakeWordDetection() }, 1000)
            }
        }

        speechRecognitionManager.onPartialResult = { partial ->
            onPartialResult?.invoke(partial)
        }

        // 🔴 REPORT WHAT HAPPENED. Until 2026-08-25 this called initialize() and then logged
        // "✓ Speech recognition initialized" unconditionally — on the line AFTER the call that
        // had just returned false and pushed onSpeechError out to the WebView. A log affirming
        // success on the path that failed, which is the same shape as the HaVoiceService engine
        // claim that hid row 54.
        //
        // And the failure itself is not news on every device: SpeechRecognizer is absent by
        // design on Fire OS and de-Googled builds (zero RecognitionService measured on the
        // Echo Show 5 and Fire HD 8) — devices whose microphones work fine and whose STT is
        // served by sherpa. Raising a user-facing voice error there says something untrue about
        // a working device, so this init is now allowed to report "absent" quietly and let the
        // provider chain do its job.
        if (speechRecognitionManager.initialize()) {
            Log.d(TAG, "✓ Speech recognition initialized")
        } else {
            Log.i(TAG, "SpeechRecognizer absent — this device's STT is served by the provider " +
                    "chain (sherpa / HA / cloud), not the OS recognizer. Not an error.")
        }
    }

    /**
     * Initialize Deepgram connection proactively
     * Call during app startup for instant first voice command
     */
    fun initializeDeepgram() {
        deepgramConnectionManager.initialize {
            Log.i(TAG, "✅ Deepgram ready for instant commands!")
        }
    }

    /**
     * Initialize Wake Word Detection
     * Now uses SharedAudioBuffer for seamless STT handoff
     */
    fun initializeWakeWord() {
        Log.d(TAG, "Initializing wake word manager with SharedAudioBuffer...")

        // Create shared audio buffer (5 seconds of audio at 16kHz)
        sharedAudioBuffer = SharedAudioBuffer(sampleRate = 16000, durationSeconds = 5.0f)
        Log.d(TAG, "✓ SharedAudioBuffer created (5 seconds capacity)")

        // Create audio capture service
        audioCaptureService = AudioCaptureService(context, sharedAudioBuffer!!)
        audioCaptureService?.onError = { error ->
            Log.e(TAG, "AudioCaptureService error: $error")
            onWakeWordError?.invoke(error)
        }
        audioCaptureService?.onStarted = {
            Log.d(TAG, "✓ AudioCaptureService started")
        }
        audioCaptureService?.onStopped = {
            Log.d(TAG, "AudioCaptureService stopped")
        }
        Log.d(TAG, "✓ AudioCaptureService created")

        // Create wake word manager
        wakeWordManager = WakeWordManager(context, handler)

        // Attach shared buffer to wake word manager
        wakeWordManager?.setSharedBuffer(sharedAudioBuffer!!)

        // Register callbacks - now includes buffer position!
        wakeWordManager?.onDetected = { confidence, bufferPosition ->
            handleWakeWordDetected(confidence, bufferPosition)
        }

        wakeWordManager?.onError = { error ->
            onWakeWordError?.invoke(error)
        }

        wakeWordManager?.onAudioLevel = { level ->
            onAudioLevel?.invoke(level)
        }

        wakeWordManager?.onHeartbeat = { confidence, volume ->
            onHeartbeat?.invoke(confidence, volume)
        }

        // Live sample collection callback (beta feature for training data)
        wakeWordManager?.onLiveSampleCollected = { wavData, metadata ->
            onLiveSampleCollected?.invoke(wavData, metadata)
        }

        // Initialize with default Alexa model
        val success = wakeWordManager?.initialize(
            modelName = "alexa_v0.1",
            threshold = 0.3f
        ) ?: false

        if (success) {
            Log.d(TAG, "✓ Wake word manager initialized")

            // Initialize Audio Recording Manager
            recordingManager = AudioRecordingManager(
                context,
                deepgramConnectionManager,
                vadProcessor,
                handler,
                executor
            )

            // Attach shared buffer to recording manager
            recordingManager?.setSharedBuffer(sharedAudioBuffer!!)

            // Wire up recording callbacks
            recordingManager?.onListeningStarted = { onListeningStarted?.invoke() }
            recordingManager?.onListeningEnded = { onListeningEnded?.invoke() }
            recordingManager?.onAudioDataCaptured = { data -> onAudioDataCaptured?.invoke(data) }
            recordingManager?.onAudioLevel = { level -> onAudioLevel?.invoke(level) }
            recordingManager?.onTranscriptPartial = { text -> onPartialResult?.invoke(text) }
            recordingManager?.onTranscriptFinal = { text -> onSpeechResult?.invoke(text) }
            recordingManager?.onRecordingError = { error -> onSpeechError?.invoke(error) }
            recordingManager?.onRecordingComplete = {
                // Restart wake word after recording
                handler.postDelayed({
                    Log.d(TAG, "Restarting wake word after recording")
                    startWakeWordDetection()
                }, 500)
            }

            Log.d(TAG, "✓ Audio recording manager initialized with SharedAudioBuffer")
        } else {
            Log.e(TAG, "✗ Failed to initialize wake word manager")
        }
    }

    // ========== WAKE WORD METHODS ==========

    /**
     * Start wake word detection
     * Also starts AudioCaptureService if not running
     */
    fun startWakeWordDetection() {
        // Start audio capture service first (if not already running)
        if (audioCaptureService?.isCapturing() != true) {
            audioCaptureService?.start()
        }

        wakeWordManager?.start()
    }

    /**
     * Stop wake word detection
     * Note: AudioCaptureService continues running for potential STT use
     */
    fun stopWakeWordDetection() {
        wakeWordManager?.stop()
    }

    /**
     * Check if wake word is active
     */
    fun isWakeWordActive(): Boolean {
        return wakeWordManager?.isActive() ?: false
    }

    /**
     * Change wake word model
     * @param modelName Model name without .onnx extension
     * @param threshold Detection threshold (0.0-1.0)
     */
    fun changeWakeWord(modelName: String, threshold: Float) {
        Log.w(TAG, "⚠️ Wake word changing not supported with Edge Impulse (fixed model)")
        Log.w(TAG, "Model change request ignored: $modelName @ $threshold")
    }

    /**
     * Set wake word detection threshold
     * @param threshold Detection confidence threshold (0.0-1.0)
     */
    fun setWakeWordThreshold(threshold: Float) {
        Log.i(TAG, "Setting wake word threshold to: $threshold")
        wakeWordManager?.setThreshold(threshold)
    }

    /**
     * Get current wake word detection threshold
     * @return Current threshold (0.0-1.0)
     */
    fun getWakeWordThreshold(): Float {
        return wakeWordManager?.getThreshold() ?: 0.55f
    }

    /**
     * Configure wake word behavior
     */
    fun setWakeWordConfig(autoRecord: Boolean, duration: Int = 5) {
        wakeWordConfig = WakeWordConfig(autoRecord, duration)
        Log.d(TAG, "Wake word config: autoRecord=$autoRecord, duration=$duration")
    }

    /**
     * Get wake word configuration as JSON
     */
    fun getWakeWordConfig(): String {
        return wakeWordConfig.toJson()
    }

    // ========== LIVE SAMPLE COLLECTION (Beta Feature) ==========

    /**
     * Enable or disable live sample collection for wake word training.
     * When enabled, audio samples are collected during detection for model training.
     *
     * @param enabled true to enable collection, false to disable
     */
    fun setLiveSampleCollectionEnabled(enabled: Boolean) {
        wakeWordManager?.setLiveSampleCollectionEnabled(enabled)
    }

    /**
     * Check if live sample collection is enabled
     * @return true if collection is enabled
     */
    fun isLiveSampleCollectionEnabled(): Boolean {
        return wakeWordManager?.isLiveSampleCollectionEnabled() ?: false
    }

    /**
     * Get live sample collection session stats for debugging
     * @return Stats string (samples collected, limits, etc.)
     */
    fun getLiveSampleCollectionStats(): String {
        return wakeWordManager?.getLiveSampleCollectionStats() ?: "not initialized"
    }

    /**
     * Handle wake word detection event
     * NOW WITH BUFFER POSITION for seamless STT handoff!
     *
     * @param confidence Detection confidence (0.0-1.0)
     * @param bufferPosition Position in SharedAudioBuffer where wake word ended
     * @private
     */
    private fun handleWakeWordDetected(confidence: Float, bufferPosition: Long) {
        Log.d(TAG, "Wake word detected with confidence: ${"%.1f".format(confidence * 100)}%")
        Log.d(TAG, "Buffer position for STT: $bufferPosition")

        // Ensure Deepgram ready IMMEDIATELY
        deepgramConnectionManager.ensureConnection {
            Log.d(TAG, "✓ Deepgram ready")
        }

        // Notify webapp with confidence (external API unchanged)
        onWakeWordDetected?.invoke(confidence)

        // Auto-start recording if configured
        if (wakeWordConfig.autoRecord) {
            Log.d(TAG, "Auto-record: ${wakeWordConfig.duration}s from buffer position $bufferPosition")

            // NO DELAY! Start reading from buffer immediately
            // The audio is already captured - just start streaming to Deepgram
            startCloudSTTCaptureFromBuffer(
                bufferPosition = bufferPosition,
                durationSeconds = wakeWordConfig.duration,
                silenceThresholdSeconds = 1.0f
            )
        } else {
            Log.d(TAG, "Auto-record disabled, restarting wake word")
            handler.postDelayed({
                startWakeWordDetection()
            }, 500)
        }
    }

    // ========== AGC METHODS ==========

    /**
     * Set AGC gain
     * DISABLED - Edge Impulse handles volume normalization automatically
     */
    fun setAGCGain(gain: Float) {
        Log.w(TAG, "⚠️ AGC not used with Edge Impulse (automatic volume normalization)")
    }

    /**
     * Get AGC gain
     * DISABLED - Edge Impulse handles volume normalization automatically
     */
    fun getAGCGain(): Float {
        Log.d(TAG, "AGC not used with Edge Impulse (automatic volume normalization)")
        return 12.0f  // Fixed value for compatibility
    }

    /**
     * Start microphone calibration
     * DISABLED - Edge Impulse handles volume normalization automatically
     */
    fun startMicrophoneCalibration(
        durationSeconds: Int = 3,
        onComplete: ((String) -> Unit)? = null
    ) {
        Log.w(TAG, "⚠️ Microphone calibration not needed with Edge Impulse (automatic volume normalization)")
        onComplete?.invoke("""{"info":"Calibration not needed with Edge Impulse - automatic volume normalization"}""")
    }

    // ========== RECORDING METHODS ==========

    /**
     * Start cloud STT capture from SharedAudioBuffer (seamless handoff)
     * This is the new fast path - no audio gap!
     */
    private fun startCloudSTTCaptureFromBuffer(
        bufferPosition: Long,
        durationSeconds: Int = 5,
        silenceThresholdSeconds: Float = 1.0f
    ) {
        val config = RecordingConfig(
            mode = RecordingMode.STREAMING,
            maxDurationSeconds = durationSeconds,
            silenceThresholdSeconds = silenceThresholdSeconds,
            autoRestartWakeWord = true,
            useStreamingSTT = true
        )

        recordingManager?.startRecordingFromBuffer(config, bufferPosition)
    }

    /**
     * Start cloud STT capture (batch or streaming)
     * Legacy method - creates new AudioRecord
     */
    fun startCloudSTTCapture(
        durationSeconds: Int = 5,
        silenceThresholdSeconds: Float = 1.0f
    ) {
        val mode = if (useStreamingSTT) RecordingMode.STREAMING else RecordingMode.BATCH

        val config = RecordingConfig(
            mode = mode,
            maxDurationSeconds = durationSeconds,
            silenceThresholdSeconds = silenceThresholdSeconds,
            autoRestartWakeWord = true,
            useStreamingSTT = useStreamingSTT
        )

        recordingManager?.startRecording(config)
    }

    /**
     * Stop cloud STT capture
     */
    fun stopCloudSTTCapture() {
        recordingManager?.stopRecording()
    }

    /**
     * Start manual recording (for testing)
     * Does NOT restart wake word after completion
     */
    fun startManualRecording(
        maxDurationSeconds: Int,
        silenceThresholdSeconds: Float = 1.0f
    ) {
        val config = RecordingConfig(
            mode = RecordingMode.MANUAL,
            maxDurationSeconds = maxDurationSeconds,
            silenceThresholdSeconds = silenceThresholdSeconds,
            autoRestartWakeWord = false,
            useStreamingSTT = useStreamingSTT
        )

        // Override callback for manual recording
        val originalCallback = recordingManager?.onRecordingComplete
        recordingManager?.onRecordingComplete = {
            Log.d(TAG, "Manual recording complete, wake word NOT restarted")
            recordingManager?.onRecordingComplete = originalCallback
        }

        recordingManager?.startRecording(config)
    }

    /**
     * Check if currently recording
     */
    fun isCurrentlyListening(): Boolean {
        return recordingManager?.isRecording() ?: false
    }

    // ========== SPEECH RECOGNITION METHODS ==========

    fun startListening() {
        speechRecognitionManager.startListening()
    }

    fun stopListening() {
        speechRecognitionManager.stopListening()
    }

    fun cancelListening() {
        speechRecognitionManager.cancelListening()
    }

    // ========== TTS METHODS ==========

    fun speak(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        utteranceId: String = "dashie_tts_${System.currentTimeMillis()}"
    ) {
        textToSpeechManager.speak(text, rate, pitch, utteranceId)
    }

    fun stopSpeaking() {
        textToSpeechManager.stopSpeaking()
    }

    fun isSpeaking(): Boolean {
        return textToSpeechManager.isSpeaking()
    }

    fun getAvailableVoices(): Set<Voice>? {
        return textToSpeechManager.getAvailableVoices()
    }

    fun setVoice(voice: Voice): Boolean {
        return textToSpeechManager.setVoice(voice)
    }

    // ========== CONFIGURATION METHODS ==========

    fun setStreamingSTT(enabled: Boolean) {
        Log.i(TAG, "Streaming STT: $enabled")
        useStreamingSTT = enabled
    }

    fun isStreamingSTT(): Boolean {
        return useStreamingSTT
    }

    fun setSilenceThreshold(silenceMs: Int) {
        deepgramConnectionManager.setSilenceThreshold(silenceMs)
    }

    fun getSilenceThreshold(): Int {
        return deepgramConnectionManager.getSilenceThreshold()
    }

    // ========== DEVICE INFO ==========

    private fun logDeviceInfo() {
        try {
            cachedDeviceInfo = DeviceInfoHelper.getDeviceInfo(context)
            Log.d(TAG, "========== DEVICE INFO ==========")
            Log.d(TAG, cachedDeviceInfo!!)
            Log.d(TAG, "=================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging device info", e)
        }
    }

    fun getDeviceInfo(): String {
        if (cachedDeviceInfo == null) {
            cachedDeviceInfo = DeviceInfoHelper.getDeviceInfo(context)
        }
        return cachedDeviceInfo ?: "{}"
    }

    // ========== SHUTDOWN ==========

    fun shutdown() {
        Log.d(TAG, "Shutting down coordinator")

        textToSpeechManager.shutdown()
        speechRecognitionManager.shutdown()

        wakeWordManager?.shutdown()
        wakeWordManager = null

        recordingManager?.shutdown()
        recordingManager = null

        // Shutdown audio capture service
        audioCaptureService?.shutdown()
        audioCaptureService = null

        // Clear shared buffer
        sharedAudioBuffer = null

        deepgramConnectionManager.shutdown()
        executor.shutdown()

        isInitialized = false
    }

    /**
     * Get memory status for wake word detector.
     * Used by MemoryMonitor for component-specific diagnostics.
     */
    fun getWakeWordMemoryStatus(): String {
        return wakeWordManager?.getMemoryStatus() ?: "not_initialized"
    }
}

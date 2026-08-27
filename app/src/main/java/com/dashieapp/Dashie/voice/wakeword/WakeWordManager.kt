package com.dashieapp.Dashie.voice.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.edition.brandName
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import com.dashieapp.Dashie.wakeword.LiveSampleCollector
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface
import com.dashieapp.Dashie.wakeword.edgeimpulse.EdgeImpulseDetector
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordConfig
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordDetector
import com.dashieapp.Dashie.wakeword.models.WakeWordEngine
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WakeWordManager
 * Manages wake word detection lifecycle and orchestration
 *
 * VERSION: Edge Impulse (replacing OpenWakeWord)
 *
 * Responsibilities:
 * - Initialize and manage EdgeImpulseDetector
 * - Start/stop wake word detection
 * - Handle detection callbacks
 * - Process audio chunks from AudioRecordingManager
 *
 * UPDATED: Now uses SharedAudioBuffer for seamless handoff to STT.
 * Detection callback includes buffer position for STT to start reading from.
 */
class WakeWordManager(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private val TAG = "WakeWordManager"

    // Wake word detector instance (EdgeImpulse or microWakeWord, selected by model suffix)
    private var detector: WakeWordDetectorInterface? = null

    // Live sample collector for training data (beta feature)
    private var sampleCollector: LiveSampleCollector? = null

    // Shared audio buffer (set via setSharedBuffer)
    private var sharedBuffer: SharedAudioBuffer? = null

    private var isActive = false

    // Callbacks (parent decides what to do with events)
    // UPDATED: onDetected now includes bufferPosition for seamless STT handoff
    var onDetected: ((confidence: Float, bufferPosition: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAudioLevel: ((Float) -> Unit)? = null
    var onHeartbeat: ((Float, Float) -> Unit)? = null

    // Live sample collection callback (beta feature)
    var onLiveSampleCollected: ((wavData: ByteArray, metadata: String) -> Unit)? = null

    /**
     * Set the shared audio buffer
     * Must be called before start() when using shared buffer mode
     */
    fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
        detector?.setSharedBuffer(buffer)

        // Create live sample collector (beta feature)
        sampleCollector = LiveSampleCollector(buffer, context).apply {
            onSampleCollected = { wavData, metadata ->
                handler.post {
                    onLiveSampleCollected?.invoke(wavData, metadata)
                }
            }
        }
        // Attach sample collector (Edge Impulse specific)
        (detector as? EdgeImpulseDetector)?.sampleCollector = sampleCollector

        Log.d(TAG, "SharedAudioBuffer attached to WakeWordManager (with LiveSampleCollector)")
    }

    /**
     * Initialize wake word detector.
     * Selects engine based on active model's version suffix:
     * - "E" or "S" → Edge Impulse
     * - "M" → microWakeWord (falls back to EI on ARM32 devices)
     *
     * @param modelName IGNORED (engine determined by active model)
     * @param threshold IGNORED (threshold from model config)
     * @return true if initialization succeeded
     */
    fun initialize(modelName: String = "hey_dashie", threshold: Float = 0.7f): Boolean {
        val modelManager = WakeWordModelManager(context)
        val activeModel = modelManager.getActiveModel()
        val engine = activeModel.engine

        // Apply per-model detection config
        MicroWakeWordConfig.probabilityCutoff = activeModel.probabilityCutoff
        MicroWakeWordConfig.slidingWindowSize = activeModel.slidingWindowSize

        Log.i(TAG, "========================================")
        Log.i(TAG, "Initializing wake word detector")
        Log.i(TAG, "Model: ${activeModel.wakeWordName} ${activeModel.version} [${engine.displayName}]")
        Log.i(TAG, "Cutoff: ${activeModel.probabilityCutoff}, Window: ${activeModel.slidingWindowSize}")
        Log.i(TAG, "========================================")

        try {
            // Create detector based on engine type
            detector = when (engine) {
                WakeWordEngine.EDGE_IMPULSE -> {
                    Log.d(TAG, "Creating EdgeImpulseDetector")
                    EdgeImpulseDetector(context).also { eiDetector ->
                        // Attach sample collector (Edge Impulse specific)
                        sampleCollector?.let { eiDetector.sampleCollector = it }
                    }
                }
                WakeWordEngine.MICRO_WAKE_WORD -> {
                    if (!MicroFrontend.isAvailable()) {
                        Log.w(TAG, "microWakeWord not available on this ABI, falling back to Edge Impulse")
                        EdgeImpulseDetector(context).also { eiDetector ->
                            sampleCollector?.let { eiDetector.sampleCollector = it }
                        }
                    } else {
                        Log.d(TAG, "Creating MicroWakeWordDetector")
                        val modelFile = modelManager.getModelFile()
                        MicroWakeWordDetector(
                            context = context,
                            modelFile = modelFile,
                            assetModelPath = activeModel.assetPath
                        )
                    }
                }
            }

            // Common setup via interface
            sharedBuffer?.let { detector?.setSharedBuffer(it) }
            registerCallbacks()

            // Initialize the detector (load model)
            val success = detector?.initialize() ?: false

            if (success) {
                Log.d(TAG, "========================================")
                Log.d(TAG, "Detector initialized: ${detector?.engineName}")
                Log.d(TAG, "Ready for wake word detection")
                Log.d(TAG, "========================================")

                // Check for model updates in background
                checkForModelUpdates()
            } else {
                Log.e(TAG, "Failed to initialize ${engine.displayName} detector")
                detector = null
            }

            return success

        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing detector: ${e.message}", e)
            onError?.invoke("Initialization exception: ${e.message}")
            detector = null
            return false
        }
    }

    /**
     * Check for wake word model updates in background.
     * Downloads newer versions silently; they take effect on next app launch.
     */
    private fun checkForModelUpdates() {
        // Intentionally unscoped: one-shot background download that should complete
        // even if the voice subsystem restarts; holds no UI references.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelManager = WakeWordModelManager(context)
                val updated = modelManager.checkForUpdates()
                if (updated) {
                    Log.i(TAG, "🔄 Wake word model update downloaded - will apply on next launch")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check for wake word model updates: ${e.message}")
                // Non-fatal - continue with current model
            }
        }
    }

    /**
     * Start wake word detection
     * Reads from SharedAudioBuffer if attached, otherwise uses internal AudioRecord
     */
    fun start() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔍 Starting Edge Impulse wake word detection...")
        Log.d(TAG, "========================================")

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permissions not granted")
            onError?.invoke("Microphone permissions not granted. Please enable in your device's settings for ${context.brandName()}.")
            return
        }

        // Check detector is initialized
        if (detector == null) {
            Log.e(TAG, "Edge Impulse detector not initialized")
            onError?.invoke("Edge Impulse detector not initialized")
            return
        }

        // Already active - skip
        if (isActive) {
            Log.d(TAG, "Wake word detection already active")
            return
        }

        isActive = true

        // Start Edge Impulse audio recording and detection
        detector?.start()

        val bufferMode = if (sharedBuffer != null) "SharedAudioBuffer" else "internal AudioRecord"
        Log.d(TAG, "✓ Edge Impulse detector is now active (using $bufferMode)")
        Log.d(TAG, "========================================")
    }

    /**
     * Stop wake word detection
     * Note: Does NOT stop AudioCaptureService - that continues running
     */
    fun stop() {
        if (!isActive) {
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "Stopping Edge Impulse wake word detection")
        Log.d(TAG, "========================================")
        isActive = false

        try {
            detector?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping wake word detection: ${e.message}")
        }
    }

    /**
     * Check if wake word detection is currently active
     */
    fun isActive(): Boolean {
        return isActive
    }

    /**
     * Process audio chunk externally (legacy path, Edge Impulse only).
     * Both detectors now read from SharedAudioBuffer internally via start().
     *
     * @param audioSamples Raw audio samples (preferably 16000 samples)
     */
    fun processAudioChunk(audioSamples: FloatArray) {
        if (!isActive) {
            return
        }

        // Only Edge Impulse supports external audio chunk processing
        val eiDetector = detector as? EdgeImpulseDetector
        if (eiDetector == null) {
            Log.w(TAG, "processAudioChunk only supported for Edge Impulse detector")
            return
        }

        eiDetector.processAudioChunk(audioSamples)
    }

    /**
     * Get the underlying detector instance
     */
    fun getDetector(): WakeWordDetectorInterface? {
        return detector
    }

    /**
     * Get the underlying detector as EdgeImpulseDetector (for EI-specific features like sample collection).
     * Returns null if the current detector is not Edge Impulse.
     */
    fun getEdgeImpulseDetector(): EdgeImpulseDetector? {
        return detector as? EdgeImpulseDetector
    }

    /**
     * Change wake word model.
     * Performs a full reinit: shutdown → select model → initialize → start.
     * Handles both same-engine and cross-engine switches (EI ↔ MWW).
     *
     * @param modelId The model ID to switch to (e.g., "mww_okay_nabu", "ei_hey_dashie")
     * @return true if the new model initialized successfully
     */
    fun changeModel(modelId: String): Boolean {
        val modelManager = WakeWordModelManager(context)
        val newModel = modelManager.selectModelById(modelId)

        Log.i(TAG, "========================================")
        Log.i(TAG, "Switching wake word model")
        Log.i(TAG, "New: ${newModel.wakeWordName} [${newModel.engine.displayName}]")
        Log.i(TAG, "========================================")

        val wasActive = isActive
        val savedBuffer = sharedBuffer  // Preserve buffer reference across reinit

        // Full shutdown of current detector
        shutdown()

        // Apply per-model detection config
        MicroWakeWordConfig.probabilityCutoff = newModel.probabilityCutoff
        MicroWakeWordConfig.slidingWindowSize = newModel.slidingWindowSize

        // Reinitialize with new model
        val success = initialize()

        // Reattach shared buffer
        if (success && savedBuffer != null) {
            setSharedBuffer(savedBuffer)
        }

        if (success && wasActive) {
            start()
        }

        Log.i(TAG, "Model switch ${if (success) "succeeded" else "FAILED"}: ${newModel.wakeWordName}")
        return success
    }

    /**
     * Set wake word detection threshold
     * @param threshold Detection confidence threshold (0.0-1.0)
     */
    fun setThreshold(threshold: Float) {
        Log.d(TAG, "Setting wake word threshold to: $threshold")
        detector?.setThreshold(threshold)
    }

    /**
     * Get current wake word detection threshold
     * @return Current threshold (0.0-1.0)
     */
    fun getThreshold(): Float {
        return detector?.getThreshold() ?: 0.55f
    }

    // ==========================================
    // Live Sample Collection (Beta Feature)
    // ==========================================

    /**
     * Enable or disable live sample collection for training data.
     * When enabled, audio samples are collected during detection for model training.
     * This is a beta feature - samples are uploaded to the database for analysis.
     *
     * @param enabled true to enable collection, false to disable
     */
    fun setLiveSampleCollectionEnabled(enabled: Boolean) {
        sampleCollector?.enabled = enabled
        Log.i(TAG, "Live sample collection ${if (enabled) "ENABLED" else "DISABLED"}")
        if (enabled) {
            Log.i(TAG, "Sample collection stats: ${sampleCollector?.getSessionStats()}")
        }
    }

    /**
     * Check if live sample collection is enabled
     * @return true if collection is enabled
     */
    fun isLiveSampleCollectionEnabled(): Boolean {
        return sampleCollector?.enabled ?: false
    }

    /**
     * Get live sample collection session stats for debugging
     * @return Stats string (samples collected, limits, etc.)
     */
    fun getLiveSampleCollectionStats(): String {
        return sampleCollector?.getSessionStats() ?: "not initialized"
    }

    /**
     * Shutdown and cleanup
     * Call this when WakeWordManager is no longer needed
     */
    fun shutdown() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Shutting down WakeWordManager (Edge Impulse)")
        Log.d(TAG, "========================================")
        stop()

        // Clean up sample collector
        sampleCollector?.shutdown()
        sampleCollector = null

        detector?.close()
        detector = null
        sharedBuffer = null
    }

    /**
     * Register all callbacks with the Edge Impulse detector
     *
     * @private
     */
    private fun registerCallbacks() {
        // Wake word detected callback - now includes buffer position!
        detector?.onWakeWordDetected = { confidence, bufferPosition ->
            handleDetection(confidence, bufferPosition)
        }

        // Error callback
        detector?.onError = { error ->
            Log.e(TAG, "Edge Impulse error: $error")
            handler.post {
                onError?.invoke(error)
            }
        }

        // Heartbeat callback (2x per second with max confidence and volume)
        detector?.onHeartbeat = { confidence, volume ->
            handler.post {
                onHeartbeat?.invoke(confidence, volume)
            }
        }
    }

    /**
     * Handle wake word detection from Edge Impulse
     * Parent (VoiceAssistantCoordinator) decides what to do next
     *
     * @param confidence Detection confidence (0.0-1.0)
     * @param bufferPosition Position in SharedAudioBuffer for STT to start reading from
     * @private
     */
    private fun handleDetection(confidence: Float, bufferPosition: Long) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🎯 WAKE WORD DETECTED BY EDGE IMPULSE!")
        Log.d(TAG, "Confidence: ${"%.1f".format(confidence * 100)}%")
        Log.d(TAG, "Buffer position: $bufferPosition")
        Log.d(TAG, "========================================")

        // Stop wake word detection temporarily
        // NOTE: AudioCaptureService continues running - only detection stops
        stop()

        // Notify parent with buffer position - let THEM decide what to do
        // (prepare Deepgram, start recording from buffer position, etc.)
        handler.post {
            onDetected?.invoke(confidence, bufferPosition)
        }
    }

    /**
     * Get memory status for diagnostics.
     * Used by MemoryMonitor for component-specific logging.
     */
    fun getMemoryStatus(): String {
        return detector?.getMemoryStatus() ?: "not_initialized"
    }
}

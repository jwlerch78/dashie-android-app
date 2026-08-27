package com.dashieapp.Dashie.voice.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.audio.WebRTCVADProcessor
import com.dashieapp.Dashie.edition.brandName
import com.dashieapp.Dashie.voice.stt.DeepgramConnectionManager
import java.util.concurrent.Executor

/**
 * AudioRecordingManager
 * Manages audio recording for speech-to-text with multiple modes
 *
 * Responsibilities:
 * - Start/stop audio recording (batch, streaming, manual modes)
 * - Handle AudioRecord lifecycle (batch mode) or read from SharedAudioBuffer (streaming mode)
 * - Integrate with VAD (WebRTC for batch, Deepgram for streaming)
 * - Provide audio data and transcription callbacks
 * - Auto-restart wake word detection when configured
 *
 * UPDATED: Streaming mode now reads from SharedAudioBuffer instead of creating its own AudioRecord.
 * This enables seamless handoff from wake word detection without audio gaps.
 *
 * Batch mode still creates its own AudioRecord for backwards compatibility with manual testing.
 */
class AudioRecordingManager(
    private val context: Context,
    private val deepgramConnectionManager: DeepgramConnectionManager,
    private val vadProcessor: WebRTCVADProcessor?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val executor: Executor
) {
    private val TAG = "AudioRecordingManager"

    companion object {
        // Timeout if no transcript received at all (user didn't speak after wake word)
        private const val NO_SPEECH_TIMEOUT_MS = 6000L

        // Client-side silence timeout after receiving a final transcript (is_final:true)
        // This is a fast backup for when speech_final doesn't fire (which is common!)
        // Deepgram's endpointing/speech_final can be reset by background noise, punctuation
        // delays (smart_format), or audio blips. This client-side timeout kicks in faster.
        private const val POST_FINAL_SILENCE_TIMEOUT_MS = 500L

        // Steady partial detection: if we receive the same partial transcript N times
        // over this duration, assume user is done speaking (sensitive mics may not trigger speech_final)
        private const val STEADY_PARTIAL_TIMEOUT_MS = 1500L  // 1.5 seconds of identical partials = done
        private const val STEADY_PARTIAL_MIN_COUNT = 2  // Need at least 2 identical partials

        // Minimum time we should listen before accepting an empty result
        // Helps catch connection issues where we return too quickly
        private const val MIN_LISTENING_TIME_MS = 1500L

        // One-word commands that should return immediately without waiting for silence timeout
        // These are known complete commands - no need to wait for more speech
        private val INSTANT_RETURN_COMMANDS = setOf(
            "calendar", "sleep", "reload", "locations", "chores",
            "rewards", "cameras", "settings", "weather", "photos",
            "play", "pause", "stop", "home", "back"
        )

        // Wake word variants that Deepgram might pick up and include in transcript
        // Only include obvious mishearings of "Dashie" - avoid common names like "Ashley"
        // that could be legitimate command content (e.g., "Ashley mowed the lawn")
        private val WAKE_WORD_VARIANTS = listOf(
            "hey dashie", "hey dashi", "hey dashy", "hey yashi",
            "dashie", "dashi", "dashy", "yashi"
        )
    }

    // Shared audio buffer (set via setSharedBuffer)
    private var sharedBuffer: SharedAudioBuffer? = null

    /**
     * Strip wake word variants from the beginning of a transcript
     * Deepgram sometimes picks up "Dashie" (as "Ashley", "Yashi", etc.) in the transcript
     */
    private fun stripWakeWord(transcript: String): String {
        val lower = transcript.lowercase().trim()
        for (variant in WAKE_WORD_VARIANTS) {
            if (lower.startsWith(variant)) {
                // Remove the wake word and any following comma/space
                val remainder = transcript.substring(variant.length).trimStart(',', ' ')
                if (remainder.isNotEmpty()) {
                    return remainder
                }
            }
        }
        return transcript
    }

    /**
     * Check if transcript is a known one-word command that should return immediately
     */
    private fun isInstantReturnCommand(transcript: String): Boolean {
        // Strip wake word first, then normalize
        val stripped = stripWakeWord(transcript)
        val normalized = stripped.lowercase().replace(Regex("[^a-z]"), "")
        return INSTANT_RETURN_COMMANDS.contains(normalized)
    }

    // Recording state
    @Volatile
    private var isListening = false

    // Streaming STT state tracking
    private var lastTranscriptTime: Long = 0
    private var hasReceivedAnyTranscript = false
    private var hasReceivedFinalTranscript = false

    // Steady partial detection state
    private var lastPartialText = ""
    private var samePartialCount = 0
    private var firstSamePartialTime = 0L
    private var recordingStartTime = 0L

    // Buffer position for streaming from SharedAudioBuffer
    private var streamingStartPosition: Long = 0

    // Callbacks (parent decides what to do with events)
    var onAudioDataCaptured: ((ByteArray) -> Unit)? = null
    var onAudioLevel: ((Float) -> Unit)? = null
    var onTranscriptPartial: ((String) -> Unit)? = null
    var onTranscriptFinal: ((String) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningEnded: (() -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null
    var onRecordingComplete: (() -> Unit)? = null  // Called after recording ends successfully

    /**
     * Set the shared audio buffer to read from
     * Must be called before using streaming mode with buffer position
     */
    fun setSharedBuffer(buffer: SharedAudioBuffer) {
        this.sharedBuffer = buffer
        Log.d(TAG, "SharedAudioBuffer attached")
    }

    /**
     * Start recording with specified configuration
     * Routes to appropriate recording mode
     */
    fun startRecording(config: RecordingConfig) {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permissions not granted")
            onRecordingError?.invoke("Microphone permissions not granted. Please enable in your device's settings for ${context.brandName()}.")
            return
        }

        if (isListening) {
            Log.w(TAG, "Already recording - cannot start new recording")
            return
        }

        when (config.mode) {
            RecordingMode.BATCH -> {
                Log.i(TAG, "📦 Using BATCH STT mode")
                startBatchRecording(config)
            }
            RecordingMode.STREAMING -> {
                Log.i(TAG, "🚀 Using STREAMING STT mode")
                startStreamingRecording(config)
            }
            RecordingMode.MANUAL -> {
                // Route to batch or streaming based on config
                if (config.useStreamingSTT) {
                    try {
                        Log.i(TAG, "🚀 Manual recording using STREAMING STT")
                        startStreamingRecording(config)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Streaming STT failed, falling back to batch: ${e.message}")
                        startBatchRecording(config)
                    }
                } else {
                    Log.i(TAG, "📦 Manual recording using BATCH STT")
                    startBatchRecording(config)
                }
            }
        }
    }

    /**
     * Start recording from a specific buffer position (after wake word detection)
     * This is the seamless handoff path - no audio gap!
     *
     * @param config Recording configuration
     * @param bufferPosition Position in SharedAudioBuffer where wake word ended
     */
    fun startRecordingFromBuffer(config: RecordingConfig, bufferPosition: Long) {
        if (sharedBuffer == null) {
            Log.e(TAG, "Cannot start from buffer - no SharedAudioBuffer attached")
            onRecordingError?.invoke("No shared audio buffer available")
            return
        }

        if (isListening) {
            Log.w(TAG, "Already recording - cannot start new recording")
            return
        }

        streamingStartPosition = bufferPosition
        Log.i(TAG, "🚀 Starting STREAMING from buffer position $bufferPosition")
        startStreamingFromBuffer(config)
    }

    /**
     * Stop recording early
     */
    fun stopRecording() {
        if (isListening) {
            Log.d(TAG, "Stopping recording early")
            isListening = false
        }
    }

    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean {
        return isListening
    }

    /**
     * BATCH MODE
     * Records entire audio chunk, then sends to Deepgram API
     * Uses WebRTC VAD for silence detection
     * Downsamples 16kHz → 8kHz on-the-fly
     *
     * NOTE: Still creates its own AudioRecord for backwards compatibility
     */
    @SuppressLint("MissingPermission")
    private fun startBatchRecording(config: RecordingConfig) {
        isListening = true
        val logPrefix = if (config.mode == RecordingMode.MANUAL) "Manual BATCH" else "BATCH"
        Log.i(TAG, "✓ Starting $logPrefix recording (max ${config.maxDurationSeconds}s, VAD silence: ${config.silenceThresholdSeconds}s)")

        handler.post { onListeningStarted?.invoke() }

        executor.execute {
            var audioRecord: AudioRecord? = null
            try {
                val sampleRate = 16000  // Record at 16kHz (VAD expects 16kHz, downsample to 8kHz for Deepgram)
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    audioFormat
                )

                val bufferSize = maxOf(minBufferSize, sampleRate * 2)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    handler.post {
                        isListening = false
                        onRecordingError?.invoke("Failed to initialize audio recording")
                        onListeningEnded?.invoke()
                    }
                    return@execute
                }

                // Initialize VAD processor
                vadProcessor?.startSession()

                audioRecord.startRecording()
                Log.i(TAG, "✓ AudioRecord started, recording at ${sampleRate}Hz...")

                // Recording loop
                val totalSamples = sampleRate * config.maxDurationSeconds
                val buffer = ShortArray(320)  // 20ms frames for VAD
                val audioData = mutableListOf<Short>()
                var samplesRead = 0
                val startTime = System.currentTimeMillis()
                var downsampleCounter = 0  // For 2:1 downsampling (16kHz → 8kHz)

                while (samplesRead < totalSamples && isListening && !Thread.currentThread().isInterrupted) {
                    val numRead = audioRecord.read(buffer, 0, buffer.size)

                    if (numRead > 0) {
                        // 1. Check for silence using WebRTC VAD
                        val vadResult = vadProcessor?.processFrame(buffer)
                        if (vadResult != null && vadResult.silenceDuration >= config.silenceThresholdSeconds && vadResult.hasDetectedSpeech) {
                            Log.i(TAG, "🔇 Silence detected (${String.format("%.2f", vadResult.silenceDuration)}s) - stopping early")
                            break
                        }

                        // 2. Downsample 16kHz → 8kHz (take every other sample)
                        for (i in 0 until numRead) {
                            if (downsampleCounter % 2 == 0) {
                                audioData.add(buffer[i])
                            }
                            downsampleCounter++
                        }

                        samplesRead += numRead

                        // 3. Calculate audio level
                        var sum = 0.0
                        for (i in 0 until numRead) {
                            val sample = buffer[i].toDouble()
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / numRead).toInt()
                        val amplitude = rms / Short.MAX_VALUE.toFloat()

                        handler.post {
                            onAudioLevel?.invoke(amplitude)
                        }

                    } else if (numRead < 0) {
                        Log.e(TAG, "Error reading audio: $numRead")
                        break
                    }
                }

                audioRecord.stop()
                audioRecord.release()

                // End VAD session
                vadProcessor?.endSession()

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "$logPrefix complete: ${audioData.size} samples (8kHz) in ${elapsed}ms")

                // Convert ShortArray to ByteArray for transmission (already at 8kHz)
                val byteArray = ByteArray(audioData.size * 2)
                for (i in audioData.indices) {
                    val shortValue = audioData[i]
                    byteArray[i * 2] = (shortValue.toInt() and 0xFF).toByte()
                    byteArray[i * 2 + 1] = ((shortValue.toInt() shr 8) and 0xFF).toByte()
                }

                // Send to cloud STT
                handler.post {
                    isListening = false
                    onAudioDataCaptured?.invoke(byteArray)
                    onListeningEnded?.invoke()

                    // Notify completion (parent decides whether to restart wake word)
                    onRecordingComplete?.invoke()
                }

            } catch (e: Exception) {
                Log.e(TAG, "✗ Exception during batch recording: ${e.message}", e)

                audioRecord?.stop()
                audioRecord?.release()

                handler.post {
                    isListening = false
                    onRecordingError?.invoke("Batch recording error: ${e.message}")
                    onListeningEnded?.invoke()

                    // Notify completion even after error (parent decides whether to restart wake word)
                    onRecordingComplete?.invoke()
                }
            }
        }
    }

    /**
     * STREAMING MODE (legacy - creates own AudioRecord)
     * Real-time transcription via Deepgram WebSocket
     * Uses Deepgram's cloud VAD for silence detection
     * Keeps connection alive between recordings
     *
     * NOTE: This is the fallback path. Prefer startStreamingFromBuffer() for wake word triggered recordings.
     */
    @SuppressLint("MissingPermission")
    private fun startStreamingRecording(config: RecordingConfig) {
        isListening = true
        val logPrefix = if (config.mode == RecordingMode.MANUAL) "Manual STREAMING" else "STREAMING"
        Log.i(TAG, "🚀 Starting $logPrefix recording (max ${config.maxDurationSeconds}s)")

        handler.post { onListeningStarted?.invoke() }

        // Ensure Deepgram connection is ready before starting recording
        deepgramConnectionManager.ensureConnection {
            executor.execute {
                var audioRecord: AudioRecord? = null
                val accumulatedTranscript = StringBuilder()
                var lastPartialTranscript = ""  // Track last partial in case no final comes

                try {
                    val sampleRate = 16000  // Deepgram streaming uses 16kHz
                    val channelConfig = AudioFormat.CHANNEL_IN_MONO
                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                    val minBufferSize = AudioRecord.getMinBufferSize(
                        sampleRate,
                        channelConfig,
                        audioFormat
                    )

                    val bufferSize = maxOf(minBufferSize, sampleRate * 2)

                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord failed to initialize")
                        handler.post {
                            isListening = false
                            onRecordingError?.invoke("Failed to initialize audio recording")
                            onListeningEnded?.invoke()
                        }
                        return@execute
                    }

                    // Reset transcript tracking state
                    lastTranscriptTime = 0
                    hasReceivedAnyTranscript = false
                    hasReceivedFinalTranscript = false

                    // Reset steady partial detection state
                    lastPartialText = ""
                    samePartialCount = 0
                    firstSamePartialTime = 0L
                    recordingStartTime = System.currentTimeMillis()

                    // Setup callbacks for THIS recording session only
                    val deepgramStreaming = deepgramConnectionManager.getStreamingSTT()

                    deepgramStreaming?.onPartialTranscript = { transcript ->
                        Log.d(TAG, "Partial: $transcript")
                        hasReceivedAnyTranscript = true  // Prevent no-speech timeout
                        lastPartialTranscript = transcript  // Save in case no final comes
                        // NOTE: Partials are NOT sent to webapp - only used for fallback if no finals arrive
                        // This prevents progressive partials from triggering multiple command processing

                        // Instant return on FIRST partial for known one-word commands
                        // Since Deepgram only sends partials ~1/sec, waiting for stability is too slow
                        // One-word commands are unambiguous - return immediately when recognized
                        if (isInstantReturnCommand(transcript)) {
                            Log.i(TAG, "⚡ Instant return on partial: recognized one-word command \"$transcript\"")
                            accumulatedTranscript.clear()
                            accumulatedTranscript.append(transcript)
                            isListening = false
                        } else {
                            // Steady partial detection for multi-word commands
                            // If we get the same partial N times over STEADY_PARTIAL_TIMEOUT_MS, user is done
                            val normalizedTranscript = transcript.lowercase().trim()
                            if (normalizedTranscript == lastPartialText && normalizedTranscript.isNotEmpty()) {
                                samePartialCount++
                                val steadyDuration = System.currentTimeMillis() - firstSamePartialTime
                                Log.d(TAG, "📊 Steady partial #$samePartialCount: \"$transcript\" (${steadyDuration}ms)")

                                if (samePartialCount >= STEADY_PARTIAL_MIN_COUNT && steadyDuration >= STEADY_PARTIAL_TIMEOUT_MS) {
                                    Log.i(TAG, "⚡ Steady partial detected after ${steadyDuration}ms - returning \"$transcript\"")
                                    accumulatedTranscript.clear()
                                    accumulatedTranscript.append(transcript)
                                    isListening = false
                                }
                            } else {
                                // New partial text - reset steady detection
                                lastPartialText = normalizedTranscript
                                samePartialCount = 1
                                firstSamePartialTime = System.currentTimeMillis()
                            }
                        }
                    }

                    deepgramStreaming?.onFinalTranscript = { transcript ->
                        Log.i(TAG, "✅ Final: $transcript")
                        lastTranscriptTime = System.currentTimeMillis()
                        hasReceivedAnyTranscript = true
                        hasReceivedFinalTranscript = true  // Now we can start watching for silence
                        accumulatedTranscript.append(transcript).append(" ")
                        lastPartialTranscript = ""  // Clear partial since we got final
                        // NOTE: Don't send individual finals - only send the complete accumulated transcript at the end

                        // Instant return for known one-word commands (no need to wait for silence)
                        if (isInstantReturnCommand(transcript)) {
                            Log.i(TAG, "⚡ Instant return: recognized one-word command \"$transcript\"")
                            isListening = false
                        }
                    }

                    deepgramStreaming?.onSpeechFinal = { transcript ->
                        // Only honor speech_final if we've received actual transcript content
                        // This prevents early termination when wake word audio triggers SpeechStarted
                        // followed by silence before user speaks their command
                        if (hasReceivedAnyTranscript && (accumulatedTranscript.isNotEmpty() || lastPartialTranscript.isNotEmpty())) {
                            Log.i(TAG, "🔚 Speech final (400ms silence) - stopping recording")
                            isListening = false
                        } else {
                            Log.d(TAG, "🔇 Ignoring speech_final - no transcript content yet (likely wake word audio)")
                        }
                    }

                    deepgramStreaming?.onUtteranceEnd = {
                        // Only honor utterance_end if we've received actual transcript content
                        if (hasReceivedAnyTranscript && (accumulatedTranscript.isNotEmpty() || lastPartialTranscript.isNotEmpty())) {
                            Log.i(TAG, "🔚 Utterance ended (1s silence) - stopping recording (backup)")
                            isListening = false
                        } else {
                            Log.d(TAG, "🔇 Ignoring utterance_end - no transcript content yet (likely wake word audio)")
                        }
                    }

                    deepgramStreaming?.onError = { error ->
                        Log.e(TAG, "Deepgram error: $error")
                        handler.post {
                            onRecordingError?.invoke("Streaming STT error: $error")
                        }
                    }

                    // Start recording immediately (connection already established)
                    audioRecord.startRecording()
                    Log.i(TAG, "✓ AudioRecord started, streaming to Deepgram...")

                    // Stream audio to Deepgram
                    val totalSamples = sampleRate * config.maxDurationSeconds
                    val buffer = ShortArray(320)
                    var samplesRead = 0
                    val startTime = System.currentTimeMillis()

                    while (samplesRead < totalSamples && isListening && !Thread.currentThread().isInterrupted) {
                        // Check for no-speech timeout (only if we haven't received any transcript)
                        if (!hasReceivedAnyTranscript) {
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed >= NO_SPEECH_TIMEOUT_MS) {
                                Log.i(TAG, "⏱️ No speech timeout: ${elapsed}ms with no transcript - giving up")
                                break
                            }
                        }

                        // Check for client-side silence timeout after final transcript
                        // This is a fast backup when speech_final doesn't fire (common with smart_format)
                        if (hasReceivedFinalTranscript && lastTranscriptTime > 0) {
                            val silenceAfterFinal = System.currentTimeMillis() - lastTranscriptTime
                            if (silenceAfterFinal >= POST_FINAL_SILENCE_TIMEOUT_MS) {
                                Log.i(TAG, "⏱️ ${silenceAfterFinal}ms silence after final transcript - stopping")
                                break
                            }
                        }

                        val numRead = audioRecord.read(buffer, 0, buffer.size)

                        if (numRead > 0) {
                            // 1. Send audio to Deepgram immediately
                            deepgramStreaming?.sendAudio(buffer, numRead)
                            samplesRead += numRead

                            // 2. Calculate audio level
                            var sum = 0.0
                            for (i in 0 until numRead) {
                                val sample = buffer[i].toDouble()
                                sum += sample * sample
                            }
                            val rms = kotlin.math.sqrt(sum / numRead).toInt()
                            val amplitude = rms / Short.MAX_VALUE.toFloat()

                            handler.post {
                                onAudioLevel?.invoke(amplitude)
                            }

                        } else if (numRead < 0) {
                            Log.e(TAG, "Error reading audio: $numRead")
                            break
                        }
                    }

                    audioRecord.stop()
                    audioRecord.release()

                    // Wait briefly for any final transcripts from Deepgram
                    // (UtteranceEnd event already triggered, just need to receive the final transcript)
                    Thread.sleep(300)

                    // Finalize to flush remaining audio (keeps connection alive!)
                    deepgramStreaming?.finalize()

                    val elapsed = System.currentTimeMillis() - startTime

                    // Build final transcript: use accumulated finals, or fall back to last partial
                    var finalTranscript = accumulatedTranscript.toString().trim()
                    if (finalTranscript.isEmpty() && lastPartialTranscript.isNotEmpty()) {
                        Log.d(TAG, "No final transcripts received, using last partial")
                        finalTranscript = lastPartialTranscript
                    }

                    // Fix 3: Handle empty transcript with minimum listening time check
                    // If we returned too quickly with empty result, likely a connection issue
                    val totalListeningTime = System.currentTimeMillis() - recordingStartTime
                    if (finalTranscript.isEmpty() && totalListeningTime < MIN_LISTENING_TIME_MS) {
                        Log.w(TAG, "⚠️ Empty transcript after only ${totalListeningTime}ms - likely connection issue, not user silence")
                        Log.w(TAG, "⚠️ Consider: Was Deepgram connection ready? Did we receive SpeechStarted?")
                    }

                    Log.i(TAG, "✅ $logPrefix complete: \"$finalTranscript\" (${elapsed}ms)")

                    // Send final transcript to parent (even if empty - webapp will handle it)
                    handler.post {
                        isListening = false
                        // Always send transcript - webapp handles empty case with "I couldn't understand" message
                        onTranscriptFinal?.invoke(finalTranscript)
                        onListeningEnded?.invoke()

                        // Notify completion (parent decides whether to restart wake word)
                        onRecordingComplete?.invoke()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "✗ Exception during streaming recording: ${e.message}", e)

                    audioRecord?.stop()
                    audioRecord?.release()

                    handler.post {
                        isListening = false
                        onRecordingError?.invoke("Streaming recording error: ${e.message}")
                        onListeningEnded?.invoke()

                        // Notify completion even after error (parent decides whether to restart wake word)
                        onRecordingComplete?.invoke()
                    }
                }
            }
        }
    }

    /**
     * STREAMING FROM BUFFER MODE (new - reads from SharedAudioBuffer)
     * Real-time transcription via Deepgram WebSocket
     * Reads from SharedAudioBuffer starting at the position where wake word ended
     * NO AUDIO GAP - command spoken immediately after wake word is captured!
     */
    private fun startStreamingFromBuffer(config: RecordingConfig) {
        isListening = true
        Log.i(TAG, "🚀 Starting STREAMING from SharedAudioBuffer (position: $streamingStartPosition, max ${config.maxDurationSeconds}s)")

        handler.post { onListeningStarted?.invoke() }

        // Ensure Deepgram connection is ready before starting
        deepgramConnectionManager.ensureConnection {
            executor.execute {
                val accumulatedTranscript = StringBuilder()
                var lastPartialTranscript = ""

                try {
                    val sampleRate = 16000
                    val chunkSize = 320  // 20ms chunks to match original

                    // Reset transcript tracking state
                    lastTranscriptTime = 0
                    hasReceivedAnyTranscript = false
                    hasReceivedFinalTranscript = false

                    // Reset steady partial detection state
                    lastPartialText = ""
                    samePartialCount = 0
                    firstSamePartialTime = 0L
                    recordingStartTime = System.currentTimeMillis()

                    // Setup callbacks for THIS recording session only
                    val deepgramStreaming = deepgramConnectionManager.getStreamingSTT()

                    deepgramStreaming?.onPartialTranscript = { transcript ->
                        Log.d(TAG, "Partial: $transcript")
                        hasReceivedAnyTranscript = true  // Prevent no-speech timeout
                        lastPartialTranscript = transcript

                        // Instant return on FIRST partial for known one-word commands
                        // Since Deepgram only sends partials ~1/sec, waiting for stability is too slow
                        // One-word commands are unambiguous - return immediately when recognized
                        if (isInstantReturnCommand(transcript)) {
                            Log.i(TAG, "⚡ Instant return on partial: recognized one-word command \"$transcript\"")
                            accumulatedTranscript.clear()
                            accumulatedTranscript.append(transcript)
                            isListening = false
                        } else {
                            // Steady partial detection for multi-word commands
                            // If we get the same partial N times over STEADY_PARTIAL_TIMEOUT_MS, user is done
                            val normalizedTranscript = transcript.lowercase().trim()
                            if (normalizedTranscript == lastPartialText && normalizedTranscript.isNotEmpty()) {
                                samePartialCount++
                                val steadyDuration = System.currentTimeMillis() - firstSamePartialTime
                                Log.d(TAG, "📊 Steady partial #$samePartialCount: \"$transcript\" (${steadyDuration}ms)")

                                if (samePartialCount >= STEADY_PARTIAL_MIN_COUNT && steadyDuration >= STEADY_PARTIAL_TIMEOUT_MS) {
                                    Log.i(TAG, "⚡ Steady partial detected after ${steadyDuration}ms - returning \"$transcript\"")
                                    accumulatedTranscript.clear()
                                    accumulatedTranscript.append(transcript)
                                    isListening = false
                                }
                            } else {
                                // New partial text - reset steady detection
                                lastPartialText = normalizedTranscript
                                samePartialCount = 1
                                firstSamePartialTime = System.currentTimeMillis()
                            }
                        }
                    }

                    deepgramStreaming?.onFinalTranscript = { transcript ->
                        Log.i(TAG, "✅ Final: $transcript")
                        lastTranscriptTime = System.currentTimeMillis()
                        hasReceivedAnyTranscript = true
                        hasReceivedFinalTranscript = true
                        accumulatedTranscript.append(transcript).append(" ")
                        lastPartialTranscript = ""

                        // Instant return for known one-word commands (no need to wait for silence)
                        if (isInstantReturnCommand(transcript)) {
                            Log.i(TAG, "⚡ Instant return: recognized one-word command \"$transcript\"")
                            isListening = false
                        }
                    }

                    deepgramStreaming?.onSpeechFinal = { transcript ->
                        // Only honor speech_final if we've received actual transcript content
                        // This prevents early termination when wake word audio triggers SpeechStarted
                        // followed by silence before user speaks their command
                        if (hasReceivedAnyTranscript && (accumulatedTranscript.isNotEmpty() || lastPartialTranscript.isNotEmpty())) {
                            Log.i(TAG, "🔚 Speech final (400ms silence) - stopping recording")
                            isListening = false
                        } else {
                            Log.d(TAG, "🔇 Ignoring speech_final - no transcript content yet (likely wake word audio)")
                        }
                    }

                    deepgramStreaming?.onUtteranceEnd = {
                        // Only honor utterance_end if we've received actual transcript content
                        if (hasReceivedAnyTranscript && (accumulatedTranscript.isNotEmpty() || lastPartialTranscript.isNotEmpty())) {
                            Log.i(TAG, "🔚 Utterance ended (1s silence) - stopping recording (backup)")
                            isListening = false
                        } else {
                            Log.d(TAG, "🔇 Ignoring utterance_end - no transcript content yet (likely wake word audio)")
                        }
                    }

                    deepgramStreaming?.onError = { error ->
                        Log.e(TAG, "Deepgram error: $error")
                        handler.post {
                            onRecordingError?.invoke("Streaming STT error: $error")
                        }
                    }

                    // Start reading from the marked buffer position
                    var currentPosition = streamingStartPosition

                    Log.i(TAG, "✓ Reading from SharedAudioBuffer (position: $currentPosition), streaming to Deepgram...")

                    val totalSamples = sampleRate * config.maxDurationSeconds
                    var samplesRead = 0
                    val startTime = System.currentTimeMillis()
                    val pollIntervalMs = 20L  // Poll every 20ms (matches chunk size)

                    while (samplesRead < totalSamples && isListening && !Thread.currentThread().isInterrupted) {
                        // Check for no-speech timeout (only if we haven't received any transcript)
                        if (!hasReceivedAnyTranscript) {
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed >= NO_SPEECH_TIMEOUT_MS) {
                                Log.i(TAG, "⏱️ No speech timeout: ${elapsed}ms with no transcript - giving up")
                                break
                            }
                        }

                        // Check for client-side silence timeout after final transcript
                        // This is a fast backup when speech_final doesn't fire (common with smart_format)
                        if (hasReceivedFinalTranscript && lastTranscriptTime > 0) {
                            val silenceAfterFinal = System.currentTimeMillis() - lastTranscriptTime
                            if (silenceAfterFinal >= POST_FINAL_SILENCE_TIMEOUT_MS) {
                                Log.i(TAG, "⏱️ ${silenceAfterFinal}ms silence after final transcript - stopping")
                                break
                            }
                        }

                        // Check how many samples are available from our position
                        val available = sharedBuffer!!.samplesAvailableFrom(currentPosition)

                        if (available >= chunkSize) {
                            // Read chunk as shorts for Deepgram
                            val audioChunk = sharedBuffer!!.readFromAsShorts(currentPosition, chunkSize)

                            // Send to Deepgram
                            deepgramStreaming?.sendAudio(audioChunk, audioChunk.size)

                            currentPosition += chunkSize
                            samplesRead += chunkSize

                            // Calculate audio level
                            var sum = 0.0
                            for (i in audioChunk.indices) {
                                val sample = audioChunk[i].toDouble()
                                sum += sample * sample
                            }
                            val rms = kotlin.math.sqrt(sum / audioChunk.size).toInt()
                            val amplitude = rms / Short.MAX_VALUE.toFloat()

                            handler.post {
                                onAudioLevel?.invoke(amplitude)
                            }
                        } else {
                            // Wait for more audio to arrive in the buffer
                            Thread.sleep(pollIntervalMs)
                        }
                    }

                    // Wait briefly for any final transcripts from Deepgram
                    Thread.sleep(300)

                    // Finalize to flush remaining audio (keeps connection alive!)
                    deepgramStreaming?.finalize()

                    val elapsed = System.currentTimeMillis() - startTime

                    // Build final transcript
                    var finalTranscript = accumulatedTranscript.toString().trim()
                    if (finalTranscript.isEmpty() && lastPartialTranscript.isNotEmpty()) {
                        Log.d(TAG, "No final transcripts received, using last partial")
                        finalTranscript = lastPartialTranscript
                    }

                    // Fix 3: Handle empty transcript with minimum listening time check
                    // If we returned too quickly with empty result, likely a connection issue
                    val totalListeningTime = System.currentTimeMillis() - recordingStartTime
                    if (finalTranscript.isEmpty() && totalListeningTime < MIN_LISTENING_TIME_MS) {
                        Log.w(TAG, "⚠️ Empty transcript after only ${totalListeningTime}ms - likely connection issue, not user silence")
                        // Still return empty, but log it distinctly so we can identify these cases
                        Log.w(TAG, "⚠️ Consider: Was Deepgram connection ready? Did we receive SpeechStarted?")
                    }

                    Log.i(TAG, "✅ STREAMING from buffer complete: \"$finalTranscript\" (${elapsed}ms, ${samplesRead} samples)")

                    // Send final transcript to parent (even if empty - webapp will handle it)
                    handler.post {
                        isListening = false
                        // Always send transcript - webapp handles empty case with "I couldn't understand" message
                        onTranscriptFinal?.invoke(finalTranscript)
                        onListeningEnded?.invoke()
                        onRecordingComplete?.invoke()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "✗ Exception during streaming from buffer: ${e.message}", e)

                    handler.post {
                        isListening = false
                        onRecordingError?.invoke("Streaming from buffer error: ${e.message}")
                        onListeningEnded?.invoke()
                        onRecordingComplete?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Shutdown and cleanup
     * Call this when AudioRecordingManager is no longer needed
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down AudioRecordingManager")
        stopRecording()
    }
}

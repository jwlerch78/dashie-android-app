package com.dashieapp.Dashie.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioCaptureService
 *
 * Owns the single AudioRecord instance and continuously writes to SharedAudioBuffer.
 * Both Edge Impulse (wake word) and Deepgram (STT) read from the shared buffer.
 *
 * Lifecycle:
 * - start(): Begin continuous audio capture
 * - stop(): Stop capture (e.g., app backgrounded, sleep mode)
 * - Runs on dedicated thread with high priority
 *
 * Benefits over previous approach:
 * - No audio gap when transitioning from wake word to STT
 * - Single AudioRecord avoids initialization latency
 * - Both consumers read from same audio stream
 */
class AudioCaptureService(
    private val context: Context,
    private val sharedBuffer: SharedAudioBuffer
) {
    private val TAG = "AudioCaptureService"

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_MS = 100  // Read in 100ms chunks (matches Edge Impulse)
    }

    private var audioRecord: AudioRecord? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    // Callbacks
    var onError: ((String) -> Unit)? = null
    var onStarted: (() -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    // Optional in-line processor (cascade AEC, WS-A.2) applied to every chunk before it
    // reaches SharedAudioBuffer — cancel once here and wake word + STT both get clean
    // audio. Strictly non-fatal: null result or a throw → the raw chunk is written.
    @Volatile var captureProcessor: MicCaptureProcessor? = null

    /**
     * Start continuous audio capture
     * Audio is written to SharedAudioBuffer for consumers to read
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return true
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission not granted")
            onError?.invoke("Microphone permission not granted")
            return false
        }

        isRunning.set(true)

        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCaptureLoop()
        }

        return true
    }

    /**
     * Stop audio capture
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }

        Log.d(TAG, "Stopping audio capture")
        isRunning.set(false)
    }

    /**
     * Stop capture and invoke [onReleased] once the AudioRecord is ACTUALLY released — not merely
     * when the stop flag is set. The capture loop runs on a single-thread executor and releases the
     * AudioRecord when it exits (see [runCaptureLoop]); queueing [onReleased] on that SAME executor
     * makes it run strictly AFTER that release, deterministically, with no delay-guess. If nothing
     * is capturing, the executor is idle and it runs immediately.
     *
     * Load-bearing for the Android SpeechRecognizer handoff: that provider opens its OWN mic, so it
     * must not do so until ours is freed — otherwise the 2nd+ session opens while our AudioRecord is
     * still releasing and the recognizer gets silence (device-confirmed 2026-07-20, follow-up STT).
     */
    fun stopAndNotify(onReleased: () -> Unit) {
        Log.d(TAG, "Stopping audio capture (awaiting AudioRecord release)")
        isRunning.set(false)
        try {
            executor.execute { onReleased() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onReleased()   // executor shut down → nothing is capturing anyway
        }
    }

    /**
     * Check if currently capturing
     */
    fun isCapturing(): Boolean {
        return isRunning.get()
    }

    /**
     * Main capture loop - runs on dedicated thread
     */
    private fun runCaptureLoop() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        Log.d(TAG, "Min buffer size: $minBufferSize bytes")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                isRunning.set(false)
                onError?.invoke("Failed to initialize audio recording")
                return
            }

            audioRecord?.startRecording()

            // Check if startRecording actually succeeded
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ AudioRecord.startRecording() failed! State: ${audioRecord?.recordingState}")
                Log.e(TAG, "❌ This usually means another component is using the audio HAL")
                isRunning.set(false)
                onError?.invoke("Failed to start recording - audio HAL busy")
                return
            }

            // 🔴 The audio already in the ring is NOT continuous with what we are about to write —
            // capture was stopped, so the position did not advance while the world kept moving.
            // Anything reading "recent history" across this seam gets the previous turn's audio
            // presented as if it were milliseconds old. After a SpeechRecognizer mic handoff that
            // history's newest content is the wake word that STARTED the turn, and both wake
            // engines re-detected it — a ghost turn after every fast turn (device-observed
            // 2026-08-24). See SharedAudioBuffer.markDiscontinuity.
            //
            // Here rather than at the handoff: EVERY capture restart has this property (sleep/wake
            // and Live-mode handoffs too), and the seam is created by this loop.
            sharedBuffer.markDiscontinuity()

            Log.d(TAG, "🔊 Audio capture started (history cleared — see markDiscontinuity) - " +
                "writing to SharedAudioBuffer id=${System.identityHashCode(sharedBuffer)}")
            onStarted?.invoke()

            // Read in 100ms chunks (1600 samples at 16kHz)
            val chunkSize = (SAMPLE_RATE * CHUNK_SIZE_MS) / 1000  // 1600 samples
            val readBuffer = ShortArray(chunkSize)

            while (isRunning.get()) {
                val samplesRead = audioRecord?.read(readBuffer, 0, chunkSize) ?: -1

                if (samplesRead > 0) {
                    val processed = try {
                        captureProcessor?.process(readBuffer, samplesRead)
                    } catch (t: Throwable) {
                        Log.w(TAG, "captureProcessor threw — raw pass-through", t); null
                    }
                    if (processed == null) {
                        // Write to shared buffer (handles Short->Float conversion)
                        sharedBuffer.writeShorts(readBuffer, samplesRead)
                        // Dev-only tap: capture the exact detector input (no-op unless enabled)
                        ContinuousCaptureRecorder.onAudio(readBuffer, samplesRead)
                    } else if (processed.isNotEmpty()) {
                        sharedBuffer.writeShorts(processed, processed.size)
                        ContinuousCaptureRecorder.onAudio(processed, processed.size)
                    }
                } else if (samplesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $samplesRead")
                    break
                }
            }

            // Cleanup
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            Log.d(TAG, "Audio capture stopped")
            onStopped?.invoke()

        } catch (e: Exception) {
            Log.e(TAG, "Exception in capture loop", e)
            isRunning.set(false)

            audioRecord?.let {
                try {
                    it.stop()
                    it.release()
                } catch (e2: Exception) {
                    Log.e(TAG, "Error releasing AudioRecord", e2)
                }
            }
            audioRecord = null

            onError?.invoke("Audio capture error: ${e.message}")
        }
    }

    /**
     * Shutdown and release resources
     */
    fun shutdown() {
        stop()
        executor.shutdown()
    }
}

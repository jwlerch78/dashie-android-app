package com.dashieapp.Dashie.audio

import android.util.Log
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SharedBufferAudioSource
 *
 * Custom AudioSource for RootEncoder that reads from SharedAudioBuffer instead of
 * creating its own AudioRecord. This allows RTSP streaming and wake word detection
 * to share the same microphone input, avoiding conflicts on devices with limited
 * audio HAL support (like Google TV).
 *
 * Design:
 * - Reads from SharedAudioBuffer (which is fed by AudioCaptureService at 16kHz)
 * - Delivers PCM frames to RootEncoder's encoder via GetMicrophoneData callback
 * - Operates at 16kHz sample rate (same as wake word detection)
 *
 * Usage:
 * - Pass this to RtspServerStream instead of MicrophoneSource when voice is enabled
 * - Call prepareAudio(16000, false, 64000) to match the shared buffer's sample rate
 */
class SharedBufferAudioSource(
    private val sharedBuffer: SharedAudioBuffer
) : AudioSource() {

    companion object {
        private const val TAG = "SharedBufferAudioSrc"
        private const val CHUNK_SIZE_MS = 20  // 20ms chunks for smooth audio delivery
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var lastReadPosition: Long = 0

    // AudioSource requires these but we don't use them since we read from shared buffer
    private var configuredSampleRate = 16000
    private var configuredStereo = false

    /**
     * Initialize the audio source.
     * We don't actually need to create anything since we read from SharedAudioBuffer.
     */
    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        configuredSampleRate = sampleRate
        configuredStereo = isStereo

        // Warn if sample rate doesn't match SharedAudioBuffer (16kHz)
        if (sampleRate != 16000) {
            Log.w(TAG, "Requested sample rate $sampleRate doesn't match SharedAudioBuffer (16000). Using 16000.")
        }
        if (isStereo) {
            Log.w(TAG, "Stereo requested but SharedAudioBuffer is mono. Using mono.")
        }

        Log.i(TAG, "🔊 SharedBufferAudioSource created: bufferId=${System.identityHashCode(sharedBuffer)} (reading from SharedAudioBuffer at 16kHz mono)")
        return true
    }

    /**
     * Start delivering audio frames to the encoder.
     */
    override fun start(getMicrophoneData: GetMicrophoneData) {
        if (running.get()) {
            Log.w(TAG, "Already running")
            return
        }

        this.getMicrophoneData = getMicrophoneData
        running.set(true)
        lastReadPosition = sharedBuffer.getCurrentPosition()

        Log.i(TAG, "Starting audio delivery from SharedAudioBuffer")

        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            deliverAudioLoop()
        }
    }

    /**
     * Stop delivering audio frames.
     */
    override fun stop() {
        if (!running.get()) {
            return
        }

        Log.i(TAG, "Stopping audio delivery")
        running.set(false)
        getMicrophoneData = null
    }

    /**
     * Check if currently running.
     */
    override fun isRunning(): Boolean = running.get()

    /**
     * Release resources.
     */
    override fun release() {
        stop()
        executor.shutdown()
        Log.i(TAG, "SharedBufferAudioSource released")
    }

    /**
     * Main loop that reads from SharedAudioBuffer and delivers to encoder.
     */
    private fun deliverAudioLoop() {
        // Calculate chunk size in samples (20ms at 16kHz = 320 samples)
        val chunkSamples = (16000 * CHUNK_SIZE_MS) / 1000  // 320 samples per 20ms

        // Sleep time between reads (slightly less than chunk duration for smooth delivery)
        val sleepMs = (CHUNK_SIZE_MS * 0.9).toLong()

        Log.d(TAG, "Audio delivery loop started: chunkSamples=$chunkSamples, sleepMs=$sleepMs")

        var loopCount = 0
        var framesDelivered = 0

        while (running.get()) {
            try {
                loopCount++

                // Heartbeat log every 500 iterations (~10 seconds)
                if (loopCount % 500 == 0) {
                    val bufferPos = sharedBuffer.getCurrentPosition()
                    Log.i(TAG, "🔄 Audio delivery alive: loop=$loopCount, frames=$framesDelivered, bufPos=$bufferPos, readPos=$lastReadPosition")
                }

                // Check if we have enough new samples
                val available = sharedBuffer.samplesAvailableFrom(lastReadPosition)

                if (available >= chunkSamples) {
                    // Read samples from shared buffer
                    val floatSamples = sharedBuffer.readFrom(lastReadPosition, chunkSamples)
                    lastReadPosition += floatSamples.size

                    // Convert to PCM16 (Short) ByteArray format for encoder
                    // Each sample is 2 bytes (16-bit PCM)
                    val pcmBytes = ByteArray(floatSamples.size * 2)
                    for (i in floatSamples.indices) {
                        val pcm16 = (floatSamples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        // Little-endian byte order
                        pcmBytes[i * 2] = (pcm16.toInt() and 0xFF).toByte()
                        pcmBytes[i * 2 + 1] = ((pcm16.toInt() shr 8) and 0xFF).toByte()
                    }

                    // Create Frame and deliver to encoder
                    // Frame constructor: (buffer: ByteArray, offset: Int, size: Int, timeStamp: Long)
                    val frame = Frame(
                        pcmBytes,
                        0,  // offset
                        pcmBytes.size,  // size
                        System.nanoTime() / 1000  // timestamp in microseconds
                    )

                    getMicrophoneData?.inputPCMData(frame)
                    framesDelivered++
                } else {
                    // Not enough samples yet, wait a bit
                    Thread.sleep(sleepMs / 2)
                }

                // Brief sleep to control delivery rate
                Thread.sleep(sleepMs)

            } catch (e: InterruptedException) {
                Log.d(TAG, "Audio delivery interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio delivery: ${e.message}", e)
            }
        }

        Log.d(TAG, "Audio delivery loop ended")
    }
}

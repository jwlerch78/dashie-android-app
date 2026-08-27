package com.dashieapp.Dashie.voice.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wake Word Sample Recorder
 *
 * Records 3-second audio clips for wake word training.
 * Streams audio data directly to webapp (no local file storage).
 */
class WakeWordSampleRecorder(private val context: Context) {
    private val TAG = "WakeWordSampleRecorder"

    // Audio configuration (matches WakeWordDetector)
    private val SAMPLE_RATE = 16000
    private val CHANNELS = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val RECORDING_DURATION_MS = 3000  // 3 seconds
    private val CHUNK_SIZE = 1280  // 80ms chunks (same as WakeWordDetector)

    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    // Callbacks
    var onRecordingProgress: ((secondsRemaining: Int) -> Unit)? = null
    var onRecordingComplete: ((success: Boolean, wavData: ByteArray?, metadata: String?, error: String?) -> Unit)? = null

    /**
     * Record a wake word sample
     * Records for 3 seconds and returns WAV data + metadata to webapp via callback
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun recordSample() {
        Log.d(TAG, "🎙️ recordSample() called (isRecording=$isRecording)")

        if (isRecording) {
            Log.e(TAG, "⚠️ Already recording a sample! Force-stopping previous recording...")
            // Force stop the previous recording
            stopRecording()
            // Wait a moment for cleanup
            Thread.sleep(200)
        }

        Log.d(TAG, "Creating new recording thread...")
        Thread {
            isRecording = true
            var recordInstance: AudioRecord? = null

            try {
                Log.d(TAG, "📝 Thread started - beginning wake word sample recording (${RECORDING_DURATION_MS}ms)")

                // Calculate buffer size
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
                val totalSamples = (SAMPLE_RATE * RECORDING_DURATION_MS) / 1000
                val audioBuffer = ShortArray(totalSamples)

                // Create AudioRecord
                // Use MIC instead of VOICE_RECOGNITION for Fire TV compatibility
                recordInstance = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNELS,
                    ENCODING,
                    bufferSize
                )
                audioRecord = recordInstance

                if (recordInstance.state != AudioRecord.STATE_INITIALIZED) {
                    throw Exception("Failed to initialize AudioRecord (state: ${recordInstance.state})")
                }

                // Start recording
                val startResult = try {
                    recordInstance.startRecording()
                    recordInstance.recordingState
                } catch (e: Exception) {
                    Log.e(TAG, "startRecording() threw exception: ${e.message}", e)
                    throw Exception("Failed to start recording: ${e.message}")
                }

                if (startResult != AudioRecord.RECORDSTATE_RECORDING) {
                    throw Exception("AudioRecord failed to start (state: $startResult)")
                }

                Log.d(TAG, "AudioRecord started successfully")

                // Record audio in chunks with progress updates
                var samplesRead = 0
                val chunkBuffer = ShortArray(CHUNK_SIZE)
                val startTime = System.currentTimeMillis()
                var lastProgressUpdate = 0L

                while (samplesRead < totalSamples && isRecording) {
                    val remaining = totalSamples - samplesRead
                    val toRead = minOf(CHUNK_SIZE, remaining)

                    val bytesRead = recordInstance.read(chunkBuffer, 0, toRead)

                    if (bytesRead < 0) {
                        throw Exception("AudioRecord read error: $bytesRead")
                    }

                    if (bytesRead > 0) {
                        chunkBuffer.copyInto(audioBuffer, samplesRead, 0, bytesRead)
                        samplesRead += bytesRead

                        // Update progress every ~500ms
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed - lastProgressUpdate >= 500) {
                            val secondsRemaining = ((RECORDING_DURATION_MS - elapsed) / 1000.0).toInt().coerceAtLeast(0)
                            lastProgressUpdate = elapsed

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onRecordingProgress?.invoke(secondsRemaining)
                            }
                        }
                    }
                }

                // Stop recording
                recordInstance.stop()
                recordInstance.release()
                audioRecord = null
                Log.d(TAG, "Recording complete: $samplesRead samples")

                // Create WAV file data
                val wavData = createWavFile(audioBuffer, samplesRead)

                // Create metadata JSON
                val metadata = createMetadata(samplesRead, wavData.size)

                // Notify completion on main thread with data
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRecordingComplete?.invoke(true, wavData, metadata, null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording failed: ${e.message}", e)
                Log.e(TAG, "Exception type: ${e.javaClass.name}")
                e.stackTrace.take(5).forEach {
                    Log.e(TAG, "  at $it")
                }

                // Cleanup
                try {
                    recordInstance?.stop()
                } catch (ex: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord: ${ex.message}")
                }
                try {
                    recordInstance?.release()
                } catch (ex: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord: ${ex.message}")
                }
                audioRecord = null

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRecordingComplete?.invoke(false, null, null, e.message)
                }
            } finally {
                isRecording = false
            }
        }.start()
    }

    /**
     * Stop recording early
     */
    fun stopRecording() {
        if (!isRecording) return

        Log.d(TAG, "Stopping recording early")
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        }
    }


    /**
     * Create WAV file with proper headers
     */
    private fun createWavFile(audioData: ShortArray, sampleCount: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val dataSize = sampleCount * (bitsPerSample / 8)
        val headerSize = 44
        val fileSize = headerSize + dataSize - 8

        val buffer = ByteBuffer.allocate(headerSize + dataSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(fileSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)  // fmt chunk size
        buffer.putShort(1)  // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * channels * bitsPerSample / 8)  // byte rate
        buffer.putShort((channels * bitsPerSample / 8).toShort())  // block align
        buffer.putShort(bitsPerSample.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        // Audio data
        for (i in 0 until sampleCount) {
            buffer.putShort(audioData[i])
        }

        return buffer.array()
    }

    /**
     * Create metadata JSON for sample
     */
    private fun createMetadata(sampleCount: Int, fileSize: Int): String {
        val metadata = JSONObject()

        val timestamp = System.currentTimeMillis()
        metadata.put("timestamp", timestamp)
        metadata.put("duration_ms", RECORDING_DURATION_MS)
        metadata.put("sample_count", sampleCount)
        metadata.put("sample_rate", SAMPLE_RATE)
        metadata.put("channels", 1)
        metadata.put("bits_per_sample", 16)
        metadata.put("file_size_bytes", fileSize)

        // Device info
        val deviceInfo = JSONObject()
        deviceInfo.put("manufacturer", android.os.Build.MANUFACTURER)
        deviceInfo.put("model", android.os.Build.MODEL)
        deviceInfo.put("android_api", android.os.Build.VERSION.SDK_INT)
        deviceInfo.put("android_version", android.os.Build.VERSION.RELEASE)
        metadata.put("device", deviceInfo)

        // Recording settings
        val recordingInfo = JSONObject()
        recordingInfo.put("audio_source", "MIC")  // Changed to MIC for Fire TV compatibility
        recordingInfo.put("purpose", "wake_word_training")
        recordingInfo.put("wake_word", "hey_dashie")
        metadata.put("recording", recordingInfo)

        return metadata.toString(2)  // Pretty print with 2-space indent
    }
}

package com.dashieapp.Dashie.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * SharedAudioBuffer
 *
 * Thread-safe circular buffer for audio samples shared between
 * wake word detection (Edge Impulse) and speech-to-text (Deepgram).
 *
 * Design:
 * - Single writer (AudioCaptureService) appends audio continuously
 * - Multiple readers can read independently:
 *   - Edge Impulse: reads sliding 1-second window for wake word detection
 *   - STT: reads from marked position forward after wake word triggers
 *
 * Thread Safety:
 * - Uses atomic write position
 * - Readers always read behind the writer
 * - No locks needed - readers tolerate slightly stale position reads
 *
 * Storage:
 * - FloatArray normalized to [-1, 1] (Edge Impulse native format)
 * - Converted to Short/Byte on read for Deepgram
 */
class SharedAudioBuffer(
    private val sampleRate: Int = 16000,
    private val durationSeconds: Float = 5.0f
) {
    private val TAG = "SharedAudioBuffer"

    // Buffer size in samples
    val capacity: Int = (sampleRate * durationSeconds).toInt()

    // Circular buffer storage (FloatArray for Edge Impulse compatibility)
    private val buffer = FloatArray(capacity)

    // Write position (total samples written, not wrapped)
    // Using Long to avoid overflow with continuous recording
    private val totalSamplesWritten = AtomicLong(0)

    /** See [discontinuityPosition]. */
    private val discontinuityAt = AtomicLong(0)

    /**
     * Write samples to the buffer
     * Called by AudioCaptureService from recording thread
     *
     * @param samples Audio samples normalized to [-1, 1]
     */
    fun write(samples: FloatArray) {
        val startPos = totalSamplesWritten.get()

        for (i in samples.indices) {
            val bufferIndex = ((startPos + i) % capacity).toInt()
            buffer[bufferIndex] = samples[i]
        }

        totalSamplesWritten.addAndGet(samples.size.toLong())
    }

    /**
     * Write samples from ShortArray (convenience for AudioRecord output)
     * Normalizes to [-1, 1] range
     *
     * @param samples Raw PCM samples from AudioRecord
     * @param count Number of samples to write
     */
    fun writeShorts(samples: ShortArray, count: Int) {
        val startPos = totalSamplesWritten.get()

        for (i in 0 until count) {
            val bufferIndex = ((startPos + i) % capacity).toInt()
            buffer[bufferIndex] = samples[i].toFloat() / 32768.0f
        }

        totalSamplesWritten.addAndGet(count.toLong())
    }

    /**
     * Read the most recent N samples (sliding window)
     * Used by Edge Impulse for wake word detection
     *
     * @param windowSize Number of samples to read (e.g., 16000 for 1 second)
     * @return FloatArray of the most recent samples, zero-padded if buffer not full yet
     */
    fun readWindow(windowSize: Int): FloatArray {
        val result = FloatArray(windowSize)
        val currentTotal = totalSamplesWritten.get()

        // If we haven't written enough samples yet, return zero-padded
        if (currentTotal < windowSize) {
            // Copy what we have to the end of the result array
            val available = currentTotal.toInt()
            for (i in 0 until available) {
                val bufferIndex = (i % capacity)
                result[windowSize - available + i] = buffer[bufferIndex]
            }
            return result
        }

        // Read the last windowSize samples
        val startSample = currentTotal - windowSize
        for (i in 0 until windowSize) {
            val bufferIndex = ((startSample + i) % capacity).toInt()
            result[i] = buffer[bufferIndex]
        }

        return result
    }

    /**
     * Mark current position for STT to start reading from
     * Called when wake word is detected
     *
     * @return Position marker (total samples written at this moment)
     */
    fun markPosition(): Long {
        return totalSamplesWritten.get()
    }

    /**
     * Read samples from a marked position forward
     * Used by STT after wake word detection
     *
     * @param fromPosition Position returned by markPosition()
     * @param maxSamples Maximum samples to read
     * @return FloatArray of samples from position to current write position (or maxSamples)
     */
    fun readFrom(fromPosition: Long, maxSamples: Int): FloatArray {
        val currentTotal = totalSamplesWritten.get()

        // Calculate how many samples are available
        val available = (currentTotal - fromPosition).toInt().coerceAtLeast(0)
        val toRead = available.coerceAtMost(maxSamples)

        if (toRead <= 0) {
            return FloatArray(0)
        }

        // Check if requested position has been overwritten
        val oldestAvailable = currentTotal - capacity
        if (fromPosition < oldestAvailable) {
            // Data has been overwritten - return from oldest available
            val adjustedStart = oldestAvailable
            val adjustedCount = (currentTotal - adjustedStart).toInt().coerceAtMost(maxSamples)
            return readFromInternal(adjustedStart, adjustedCount)
        }

        return readFromInternal(fromPosition, toRead)
    }

    private fun readFromInternal(fromPosition: Long, count: Int): FloatArray {
        val result = FloatArray(count)
        for (i in 0 until count) {
            val bufferIndex = ((fromPosition + i) % capacity).toInt()
            result[i] = buffer[bufferIndex]
        }
        return result
    }

    /**
     * Read samples as ShortArray for Deepgram
     * Converts from Float [-1, 1] back to Short range
     *
     * @param fromPosition Position to start reading from
     * @param maxSamples Maximum samples to read
     * @return ShortArray for Deepgram streaming
     */
    fun readFromAsShorts(fromPosition: Long, maxSamples: Int): ShortArray {
        val floats = readFrom(fromPosition, maxSamples)
        return ShortArray(floats.size) { i ->
            (floats[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Get current write position
     * Can be used to calculate how far behind a reader is
     */
    fun getCurrentPosition(): Long {
        return totalSamplesWritten.get()
    }

    /**
     * Check if data at a position is still available (not overwritten)
     */
    fun isPositionValid(position: Long): Boolean {
        val currentTotal = totalSamplesWritten.get()
        val oldestAvailable = currentTotal - capacity
        return position >= oldestAvailable && position <= currentTotal
    }

    /**
     * Get number of samples available from a position
     */
    fun samplesAvailableFrom(position: Long): Int {
        val currentTotal = totalSamplesWritten.get()
        return (currentTotal - position).toInt().coerceAtLeast(0)
    }

    /**
     * Reset buffer state
     * Call when stopping/restarting audio capture
     *
     * ⚠️ This rewinds [totalSamplesWritten] to 0, which INVALIDATES every position any consumer is
     * holding (`wakeWordBufferPosition`, an STT stage's read cursor). Safe only when nothing is
     * mid-turn. To clear stale history *without* breaking positions, use [markDiscontinuity].
     */
    fun reset() {
        totalSamplesWritten.set(0)
        buffer.fill(0f)
    }

    /**
     * Declare that the audio before the current position is NOT continuous with what comes next,
     * and must never be read as recent history: zero the ring while leaving the position counter
     * monotonic.
     *
     * 🔴 **The defect this exists for (device-observed 2026-08-24, John's HA-Assist pass).** When
     * the mic is handed to a provider that captures its own audio (SpeechRecognizer), Dashie's
     * capture stops for the whole session and NOTHING is written here. Position does not advance —
     * so on restart, ring position `N-1` sits immediately behind `N` while being **2.3 seconds
     * older in wall time**, and the newest thing in that history is the wake word that STARTED the
     * turn. Every consumer whose window spans the restart read a spliced signal containing "Hey
     * Dashie":
     *  - `MicroWakeWordDetector`'s re-arm back-fill + its 1.5 s priming lead-in,
     *  - `EdgeImpulseDetector`'s ~1 s classification window,
     *
     * and BOTH scored 99–100%, so the AND-gate corroborated a wake nobody spoke — one ghost turn
     * after every fast turn (measured: constant 0.53 s offset, 7/7). Slow turns hid it, because a
     * long spoken reply gave capture time to overwrite the seam before the re-arm.
     *
     * 📌 Fixed HERE rather than in each detector on purpose. Three consumers read across this seam
     * and a per-consumer guard would be three copies of one fact — the mirror shape Standing Rule 1
     * exists to stop. A discontinuity is a property of the BUFFER, and the buffer is what knows.
     *
     * Effect on the wake path: a post-handoff re-arm now primes on silence and waits for live
     * audio, i.e. it behaves exactly like a COLD START — which the vc184 full-placement sweep
     * already verifies is clean.
     */
    fun markDiscontinuity() {
        buffer.fill(0f)
        discontinuityAt.set(totalSamplesWritten.get())
    }

    /**
     * Position of the most recent [markDiscontinuity] — i.e. the oldest sample that is part of the
     * CURRENT continuous run of audio. Consumers reading "recent history" must not reach back past
     * this, and must not trust a freshly-reset model until enough audio has arrived AFTER it.
     *
     * 0 until the first discontinuity, which is correct: app start is itself a discontinuity, and
     * nothing before position 0 exists.
     */
    val discontinuityPosition: Long get() = discontinuityAt.get()
}

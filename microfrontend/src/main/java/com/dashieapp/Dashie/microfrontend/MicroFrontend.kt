package com.dashieapp.Dashie.microfrontend

import android.os.Build
import android.util.Log
import java.io.Closeable

/** ABIs for which the native libmicrofrontend.so is bundled. */
private val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86_64")

/**
 * Checks whether the current device supports the MicroFrontend native library.
 *
 * The native library is built for armeabi-v7a, arm64-v8a and x86_64 (the frontend is
 * fixed-point integer math — no 64-bit requirement). Only 32-bit x86 is unsupported.
 *
 * @return true if at least one of the device's supported ABIs has a bundled native library
 */
val isMicroFrontendSupported: Boolean by lazy { Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS } }

/**
 * JNI wrapper for TFLite Micro Frontend audio feature extraction.
 *
 * This class provides fixed-point 16-bit feature extraction that matches
 * the ESPHome microWakeWord implementation, ensuring compatibility with
 * models trained using the TFLite Micro Frontend.
 *
 * The native implementation uses hardcoded settings that match ESPHome preprocessor_settings.h:
 * - 40 mel filterbank bins
 * - 125-7500 Hz frequency range
 * - 30ms window size
 * - PCAN strength 0.95
 *
 * **Thread Safety**: This class is NOT thread-safe. Each instance maintains
 * internal state for noise reduction and PCAN, so each thread should
 * use its own instance.
 *
 * Callers should check [isMicroFrontendSupported] before constructing an instance.
 * On unsupported architectures, the native library is not bundled and class loading
 * will fail with [UnsatisfiedLinkError].
 *
 * Based on the Home Assistant Android app microfrontend module
 * and brownard/Ava for the inspiration on the native implementation.
 *
 * @param stepSizeMs Step size between frames in milliseconds
 * @param sampleRate Audio sample rate in Hz
 */
class MicroFrontend(private val stepSizeMs: Int, private val sampleRate: Int = DEFAULT_SAMPLE_RATE) : Closeable {

    private var nativeHandle: Long = 0

    init {
        // Constructors don't require a prior isAvailable() call — load here so direct
        // construction (e.g. the WAV test harness) works before any detector has run.
        check(isAvailable()) { "microfrontend native library unavailable on this device" }
        nativeHandle = nativeCreate(sampleRate, stepSizeMs)
        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to create native MicroFrontend")
        }
        Log.d(TAG, "MicroFrontend created (sampleRate=$sampleRate, stepSizeMs=$stepSizeMs)")
    }

    /**
     * Process audio samples and extract spectrogram features.
     *
     * @param samples 16-bit PCM audio samples at the configured sample rate
     * @return List of feature frames, each containing 40 mel filterbank float values
     */
    @Suppress("UNCHECKED_CAST")
    fun processSamples(samples: ShortArray): List<FloatArray> {
        check(nativeHandle != 0L) { "MicroFrontend has been closed" }
        return nativeProcessSamples(nativeHandle, samples) as List<FloatArray>
    }

    /**
     * Reset internal state (noise estimates, PCAN state, sample buffer).
     * Call this when starting a new audio stream or restarting detection.
     */
    fun reset() {
        check(nativeHandle != 0L) { "MicroFrontend has been closed" }
        nativeReset(nativeHandle)
        Log.d(TAG, "MicroFrontend reset")
    }

    override fun close() {
        if (nativeHandle != 0L) {
            Log.d(TAG, "Closing MicroFrontend")
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    protected fun finalize() {
        close()
    }

    companion object {
        private const val TAG = "MicroFrontend"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val FEATURE_SIZE = 40  // Number of mel filterbank bins per frame

        private var libraryLoaded = false

        /**
         * Check if the native library is available on this device.
         * Returns false only when no bundled ABI matches (e.g., 32-bit x86)
         * or the library fails to load.
         */
        fun isAvailable(): Boolean {
            if (!isMicroFrontendSupported) return false
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("microfrontend")
                libraryLoaded = true
                Log.d(TAG, "Loaded microfrontend native library")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "microfrontend native library not available: ${e.message}")
                false
            }
        }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, stepSizeMs: Int): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeProcessSamples(handle: Long, samples: ShortArray): Any

        @JvmStatic
        private external fun nativeReset(handle: Long)
    }
}

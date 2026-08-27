package com.dashieapp.Dashie.wakeword.microwakeword

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug utility: processes a WAV file through the exact same MWW pipeline
 * as MicroWakeWordDetector, logging features and probabilities at every step.
 *
 * Usage from JS bridge or debug command:
 *   MicroWakeWordWavTest.processWavFile(context, "/sdcard/Download/test.wav")
 *
 * Then compare logcat output frame-by-frame with Python benchmark.
 *
 * adb push test.wav /sdcard/Download/
 * adb logcat -s MWWWavTest | grep -E "FEAT|PROB|QUANT"
 */
object MicroWakeWordWavTest {

    private const val TAG = "MWWWavTest"

    /**
     * Returns a JSON summary (also fully logged to logcat) so callers — notably the
     * :2323 `testMwwWav` command — can diff runs across devices/ABIs without adb.
     */
    fun processWavFile(
        context: Context,
        wavPath: String,
        assetModelPath: String = "models/mww/hey_dashie.tflite"
    ): org.json.JSONObject {
        val summary = org.json.JSONObject()
        summary.put("wavPath", wavPath)
        summary.put("model", assetModelPath)
        summary.put("is64BitProcess", android.os.Process.is64Bit())
        summary.put("supportedAbis", android.os.Build.SUPPORTED_ABIS.joinToString(","))

        Log.i(TAG, "============================================================")
        Log.i(TAG, "MWW WAV Test: $wavPath")
        Log.i(TAG, "Model: $assetModelPath")
        Log.i(TAG, "============================================================")

        // Read WAV file
        val samples = readWav(wavPath)
        if (samples == null) {
            Log.e(TAG, "Failed to read WAV file")
            return summary.put("error", "Failed to read WAV file")
        }
        Log.i(TAG, "Loaded ${samples.size} samples (${samples.size / 16000f}s)")
        Log.i(TAG, "Signal: max_abs=${samples.maxOf { kotlin.math.abs(it.toInt()) }}, " +
                "rms=${kotlin.math.sqrt(samples.map { it.toFloat() * it.toFloat() }.average()).toInt()}")

        // Create MicroFrontend (same as detector)
        val microFrontend = MicroFrontend(
            stepSizeMs = MicroWakeWordConfig.FEATURE_STEP_SIZE_MS,
            sampleRate = MicroWakeWordConfig.SAMPLE_RATE
        )

        // Create classifier (same as detector)
        val classifier = MicroWakeWordClassifier(
            context = context,
            assetModelPath = assetModelPath
        )
        if (!classifier.initialize()) {
            Log.e(TAG, "Failed to initialize classifier")
            return summary.put("error", "Failed to initialize classifier")
        }

        // Reset state
        classifier.reset()
        microFrontend.reset()

        // Process exactly like runDetectionLoop()
        val featureFrames = mutableListOf<FloatArray>()
        val probabilities = mutableListOf<Float>()
        val slidingWindow = ArrayDeque<Float>()
        var inferenceCount = 0
        var chunkCount = 0

        // Process 160 samples (10ms) at a time
        var offset = 0
        while (offset + MicroWakeWordConfig.SAMPLES_PER_CHUNK <= samples.size) {
            chunkCount++
            val chunk = samples.copyOfRange(offset, offset + MicroWakeWordConfig.SAMPLES_PER_CHUNK)
            offset += MicroWakeWordConfig.SAMPLES_PER_CHUNK

            // Log first few chunks' raw samples
            if (chunkCount <= 3) {
                Log.d(TAG, "CHUNK[$chunkCount] first 10 samples: ${chunk.take(10).toList()}")
            }

            // Extract features (same as detector)
            val frames = microFrontend.processSamples(chunk)

            for (frame in frames) {
                featureFrames.add(frame)

                // Log every feature frame
                Log.d(TAG, "FEAT[${featureFrames.size - 1}] first10: ${frame.take(10).map { "%.4f".format(it) }}")

                // Run inference every 3 frames (same as detector)
                if (featureFrames.size >= MicroWakeWordConfig.STRIDE_FRAMES) {
                    inferenceCount++

                    // Log the quantized input (same math as classifier)
                    logQuantizedInput(featureFrames, classifier)

                    val probability = classifier.classify(featureFrames)
                    featureFrames.clear()

                    probabilities.add(probability)

                    // Sliding window (same as detector)
                    slidingWindow.addLast(probability)
                    while (slidingWindow.size > MicroWakeWordConfig.slidingWindowSize) {
                        slidingWindow.removeFirst()
                    }

                    val avg = if (slidingWindow.isNotEmpty())
                        slidingWindow.sum() / slidingWindow.size else 0f

                    Log.i(TAG, "PROB[$inferenceCount] single=${"%.4f".format(probability)} " +
                            "avg=${"%.4f".format(avg)} " +
                            "window=${slidingWindow.map { "%.3f".format(it) }}")
                }
            }
        }

        // Summary
        Log.i(TAG, "============================================================")
        Log.i(TAG, "SUMMARY: $chunkCount chunks, $inferenceCount inferences")
        summary.put("chunks", chunkCount)
        summary.put("inferences", inferenceCount)
        if (probabilities.isNotEmpty()) {
            Log.i(TAG, "Max single prob: ${"%.4f".format(probabilities.max())}")
            val maxAvg = if (probabilities.size >= MicroWakeWordConfig.slidingWindowSize) {
                probabilities.windowed(MicroWakeWordConfig.slidingWindowSize)
                    .maxOf { window -> window.sum() / window.size }
            } else {
                probabilities.sum() / probabilities.size
            }
            Log.i(TAG, "Max sliding avg: ${"%.4f".format(maxAvg)}")
            Log.i(TAG, "All probs: ${probabilities.map { "%.3f".format(it) }}")
            summary.put("maxProb", probabilities.max().toDouble())
            summary.put("maxSlidingAvg", maxAvg.toDouble())
            summary.put("probs", org.json.JSONArray(probabilities.map { "%.4f".format(it) }))
        }
        Log.i(TAG, "============================================================")
        return summary
    }

    /**
     * Log the quantized input bytes for comparison with Python.
     * Mirrors MicroWakeWordClassifier.classify() quantization exactly.
     */
    private fun logQuantizedInput(
        features: List<FloatArray>,
        classifier: MicroWakeWordClassifier
    ) {
        // Access classifier's quantization params via reflection for debug logging
        try {
            val scaleField = classifier.javaClass.getDeclaredField("inputScale")
            scaleField.isAccessible = true
            val inputScale = scaleField.getFloat(classifier)

            val zpField = classifier.javaClass.getDeclaredField("inputZeroPoint")
            zpField.isAccessible = true
            val inputZeroPoint = zpField.getInt(classifier)

            val quantized = mutableListOf<Int>()
            for (frame in features) {
                for (value in frame) {
                    val q = ((value / inputScale) + inputZeroPoint)
                        .toInt()
                        .coerceIn(-128, 127)
                    quantized.add(q)
                }
            }

            // Log first 10 quantized values for each frame
            for (i in features.indices) {
                val start = i * 40
                val frameQ = quantized.subList(start, minOf(start + 10, quantized.size))
                Log.d(TAG, "QUANT[frame$i] first10: $frameQ")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not log quantized input: ${e.message}")
        }
    }

    /**
     * Read a 16-bit PCM WAV file into a ShortArray.
     */
    private fun readWav(path: String): ShortArray? {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $path")
                return null
            }

            val raf = RandomAccessFile(file, "r")

            // Read WAV header
            val riff = ByteArray(4)
            raf.read(riff)
            if (String(riff) != "RIFF") {
                Log.e(TAG, "Not a RIFF file")
                return null
            }

            raf.skipBytes(4) // file size

            val wave = ByteArray(4)
            raf.read(wave)
            if (String(wave) != "WAVE") {
                Log.e(TAG, "Not a WAVE file")
                return null
            }

            // Find fmt chunk
            var sampleRate = 0
            var bitsPerSample = 0
            var numChannels = 0
            var dataSize = 0

            while (raf.filePointer < raf.length()) {
                val chunkId = ByteArray(4)
                raf.read(chunkId)
                val chunkSizeBuf = ByteArray(4)
                raf.read(chunkSizeBuf)
                val chunkSize = ByteBuffer.wrap(chunkSizeBuf).order(ByteOrder.LITTLE_ENDIAN).int

                when (String(chunkId)) {
                    "fmt " -> {
                        val fmtBuf = ByteArray(chunkSize)
                        raf.read(fmtBuf)
                        val fmt = ByteBuffer.wrap(fmtBuf).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = fmt.short.toInt()
                        numChannels = fmt.short.toInt()
                        sampleRate = fmt.int
                        fmt.int // byte rate
                        fmt.short // block align
                        bitsPerSample = fmt.short.toInt()
                        Log.i(TAG, "WAV: ${sampleRate}Hz, ${bitsPerSample}bit, ${numChannels}ch, fmt=$audioFormat")
                    }
                    "data" -> {
                        dataSize = chunkSize
                        break // data follows
                    }
                    else -> {
                        raf.skipBytes(chunkSize)
                    }
                }
            }

            if (bitsPerSample != 16 || sampleRate != 16000) {
                Log.e(TAG, "Expected 16kHz 16-bit WAV, got ${sampleRate}Hz ${bitsPerSample}bit")
                return null
            }

            // Read PCM data
            val numSamples = dataSize / 2 / numChannels
            val samples = ShortArray(numSamples)
            val dataBuf = ByteArray(dataSize)
            raf.read(dataBuf)
            raf.close()

            val bb = ByteBuffer.wrap(dataBuf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                samples[i] = bb.short
                if (numChannels == 2) bb.short // skip right channel
            }

            samples
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WAV: ${e.message}", e)
            null
        }
    }
}

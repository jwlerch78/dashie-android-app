package com.dashieapp.Dashie.wakeword.edgeimpulse

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite Classifier for Wake Word Detection
 *
 * Loads the Edge Impulse TFLite model and runs inference on MFE features.
 * This replaces the native C++ SDK inference with pure Kotlin/TFLite.
 *
 * Supports loading models from:
 * 1. External storage (downloaded models via WakeWordModelManager)
 * 2. Bundled assets (fallback)
 *
 * Model details (from Edge Impulse export):
 * - Input: 3960 INT8 features (99 frames × 40 mel filters), quantized
 * - Output: 2 INT8 values (hey_dashie, unknown)
 * - Input quantization: zero_point=-128, scale=0.00390625 (1/256) - same as output
 * - Output quantization: zero_point=-128, scale=0.00390625 (1/256)
 *
 * The MFE features are already in [0, 1] range after normalization.
 * We quantize them to INT8 before inference.
 */
class TFLiteClassifier(
    private val context: Context,
    private val externalModelFile: File? = null,
    // Bundled model asset (fallback when no external file). Defaults to hey_dashie;
    // dual-engine models with their own EI leg (chickadee) pass their asset here.
    private val bundledAssetPath: String = "models/hey_dashie.tflite"
) {

    private val TAG = "TFLiteClassifier"

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    // Bundled model file path in assets (fallback)
    private val BUNDLED_MODEL_PATH = bundledAssetPath

    // Track which model is loaded
    private var loadedModelPath: String? = null

    // Pre-allocate input/output buffers
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer

    // Quantization parameters (from model_variables.h)
    // Both input and output use the same quantization: zero_point=-128, scale=1/256
    private val QUANT_ZERO_POINT = -128
    private val QUANT_SCALE = 0.00390625f  // 1/256

    init {
        // Allocate input buffer for 3960 INT8 features (1 byte each)
        inputBuffer = ByteBuffer.allocateDirect(EdgeImpulseConfig.NN_INPUT_SIZE)
        inputBuffer.order(ByteOrder.nativeOrder())

        // Allocate output buffer for 2 INT8 values
        outputBuffer = ByteBuffer.allocateDirect(EdgeImpulseConfig.NN_OUTPUT_COUNT)
        outputBuffer.order(ByteOrder.nativeOrder())

        // Load model
        try {
            val modelBuffer = loadModel()
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)
            isInitialized = true
            Log.i(TAG, "TFLite interpreter initialized - model: $loadedModelPath")

            // Log tensor info for debugging
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            Log.d(TAG, "  Input shape: ${inputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "  Input type: ${inputTensor?.dataType()}")
            Log.d(TAG, "  Output shape: ${outputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "  Output type: ${outputTensor?.dataType()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite interpreter", e)
            isInitialized = false
        }
    }

    /**
     * Load TFLite model from external file or bundled assets
     *
     * Priority:
     * 1. External model file (if provided and exists)
     * 2. Bundled asset model (fallback)
     */
    private fun loadModel(): ByteBuffer {
        // Try external model file first
        if (externalModelFile != null && externalModelFile.exists()) {
            Log.i(TAG, "Loading external model: ${externalModelFile.absolutePath}")
            loadedModelPath = externalModelFile.absolutePath
            return loadFromFile(externalModelFile)
        }

        // Fallback to bundled asset
        Log.i(TAG, "Loading bundled model from assets: $BUNDLED_MODEL_PATH")
        loadedModelPath = "assets:$BUNDLED_MODEL_PATH"
        return loadFromAssets()
    }

    /**
     * Load model from external file
     */
    private fun loadFromFile(file: File): MappedByteBuffer {
        val fileInputStream = FileInputStream(file)
        val fileChannel = fileInputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    /**
     * Load model from bundled assets
     */
    private fun loadFromAssets(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(BUNDLED_MODEL_PATH)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Get the path of the currently loaded model
     */
    fun getLoadedModelPath(): String? = loadedModelPath

    /**
     * Check if classifier is ready
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Run inference on MFE features
     *
     * @param features MFE feature vector (3960 float32 values in [0, 1] range)
     * @return ClassificationResult with scores and timing, or null on error
     */
    fun classify(features: FloatArray): ClassificationResult? {
        if (!isInitialized || interpreter == null) {
            Log.e(TAG, "Classifier not initialized")
            return null
        }

        if (features.size != EdgeImpulseConfig.NN_INPUT_SIZE) {
            Log.e(TAG, "Invalid feature size: expected ${EdgeImpulseConfig.NN_INPUT_SIZE}, got ${features.size}")
            return null
        }

        try {
            val startTime = System.nanoTime()

            // Quantize float features to INT8 and copy to input buffer
            // Quantization formula: int8_value = round(float_value / scale) + zero_point
            // With scale=1/256 and zero_point=-128:
            // int8_value = round(float_value * 256) - 128
            inputBuffer.rewind()
            for (f in features) {
                // Clamp input to [0, 1] range
                val clamped = f.coerceIn(0f, 1f)
                // Quantize: scale is 1/256, so multiply by 256 and add zero_point
                val quantized = (clamped / QUANT_SCALE + QUANT_ZERO_POINT).toInt().coerceIn(-128, 127)
                inputBuffer.put(quantized.toByte())
            }

            // Run inference
            outputBuffer.rewind()
            interpreter!!.run(inputBuffer, outputBuffer)

            val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000

            // Read INT8 output and dequantize
            outputBuffer.rewind()
            val scores = mutableMapOf<String, Float>()

            for (i in 0 until EdgeImpulseConfig.NN_OUTPUT_COUNT) {
                val int8Value = outputBuffer.get().toInt()  // Read as signed byte
                // Dequantize: float_value = (int8_value - zero_point) * scale
                val floatValue = (int8Value - QUANT_ZERO_POINT) * QUANT_SCALE
                // Clamp to [0, 1]
                val clampedValue = floatValue.coerceIn(0f, 1f)
                scores[EdgeImpulseConfig.LABELS[i]] = clampedValue
            }

            return ClassificationResult(
                scores = scores,
                dspTimeMs = 0,  // MFE time tracked separately
                classificationTimeMs = inferenceTimeMs.toInt(),
                anomalyTimeMs = 0
            )

        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            return null
        }
    }

    /**
     * Close the interpreter and release resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
        Log.d(TAG, "TFLite interpreter closed")
    }

    /**
     * Result class for classification
     */
    data class ClassificationResult(
        val scores: Map<String, Float>,
        val dspTimeMs: Int,
        val classificationTimeMs: Int,
        val anomalyTimeMs: Int
    ) {
        /**
         * Get the score for the wake word label ("hey_dashie").
         */
        val wakeWordScore: Float
            get() = scores["hey_dashie"] ?: 0f

        /**
         * Check if wake word was detected at given threshold.
         */
        fun isWakeWordDetected(threshold: Float = 0.7f): Boolean {
            return wakeWordScore >= threshold
        }
    }
}

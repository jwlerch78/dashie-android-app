package com.dashieapp.Dashie.wakeword.microwakeword

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
 * TFLite streaming classifier for microWakeWord models.
 *
 * Unlike Edge Impulse (which processes a full 1-second window per inference),
 * microWakeWord models are streaming: they maintain internal state and process
 * only 30ms of new audio per inference call.
 *
 * Input:  [1, 3, 40] — 3 feature frames x 40 mel bins (quantized INT8)
 * Output: [1, 1]     — single probability (quantized INT8)
 *
 * The interpreter must NOT be recreated between calls — it holds internal state.
 * Call reset() to clear state when restarting detection.
 */
class MicroWakeWordClassifier(
    private val context: Context,
    private val modelFile: File? = null,
    private val assetModelPath: String? = null
) {
    private val TAG = "MWWClassifier"

    private var interpreter: Interpreter? = null

    // Quantization params (read from model)
    private var inputZeroPoint = 0
    private var inputScale = 1f
    private var outputZeroPoint = 0
    private var outputScale = 1f

    // Pre-allocated buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null

    fun initialize(): Boolean {
        try {
            val modelBuffer = loadModel() ?: return false

            val options = Interpreter.Options().apply {
                setNumThreads(1)  // Streaming model is tiny, 1 thread is fine
            }
            interpreter = Interpreter(modelBuffer, options)

            // Read quantization params from input tensor
            val inputTensor = interpreter!!.getInputTensor(0)
            val inputParams = inputTensor.quantizationParams()
            inputZeroPoint = inputParams.zeroPoint
            inputScale = inputParams.scale
            Log.i(TAG, "Input tensor: shape=${inputTensor.shape().toList()}, " +
                    "dtype=${inputTensor.dataType()}, " +
                    "scale=$inputScale, zeroPoint=$inputZeroPoint")

            // Read quantization params from output tensor
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputParams = outputTensor.quantizationParams()
            outputZeroPoint = outputParams.zeroPoint
            outputScale = outputParams.scale
            Log.i(TAG, "Output tensor: shape=${outputTensor.shape().toList()}, " +
                    "dtype=${outputTensor.dataType()}, " +
                    "scale=$outputScale, zeroPoint=$outputZeroPoint")

            // Allocate input buffer: [1, 3, 40] INT8
            val inputSize = MicroWakeWordConfig.INPUT_FRAMES * MicroWakeWordConfig.INPUT_FEATURES
            inputBuffer = ByteBuffer.allocateDirect(inputSize).apply {
                order(ByteOrder.nativeOrder())
            }

            // Allocate output buffer: [1, 1] INT8
            outputBuffer = ByteBuffer.allocateDirect(1).apply {
                order(ByteOrder.nativeOrder())
            }

            Log.d(TAG, "Classifier initialized successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize classifier", e)
            return false
        }
    }

    /**
     * Run inference on 3 feature frames (30ms of audio).
     *
     * @param features 3 FloatArrays of 40 features each (from MicroFrontend)
     * @return probability [0, 1] of wake word being present
     */
    fun classify(features: List<FloatArray>): Float {
        val interp = interpreter ?: return 0f

        // Quantize float features to INT8
        inputBuffer!!.rewind()
        for (frame in features) {
            for (value in frame) {
                val quantized = ((value / inputScale) + inputZeroPoint)
                    .toInt()
                    .coerceIn(-128, 127)
                    .toByte()
                inputBuffer!!.put(quantized)
            }
        }

        // Run inference
        outputBuffer!!.rewind()
        interp.run(inputBuffer, outputBuffer)

        // Dequantize output
        outputBuffer!!.rewind()
        val rawOutput = outputBuffer!!.get().toInt() and 0xFF  // Unsigned byte
        val probability = (rawOutput - outputZeroPoint) * outputScale

        return probability.coerceIn(0f, 1f)
    }

    /**
     * Reset the interpreter's internal state.
     * Must be called when restarting detection to clear streaming state.
     */
    fun reset() {
        // Resetting tensors clears the internal streaming state
        try {
            interpreter?.resetVariableTensors()
            Log.d(TAG, "Interpreter state reset")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset interpreter state: ${e.message}")
            // Some models don't have variable tensors - that's OK
        }
    }

    fun isReady(): Boolean = interpreter != null

    fun close() {
        interpreter?.close()
        interpreter = null
        inputBuffer = null
        outputBuffer = null
    }

    private fun loadModel(): MappedByteBuffer? {
        return try {
            if (modelFile != null && modelFile.exists()) {
                // Load from downloaded file
                Log.i(TAG, "Loading model from file: ${modelFile.absolutePath}")
                val inputStream = FileInputStream(modelFile)
                val channel = inputStream.channel
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            } else if (assetModelPath != null) {
                // Load from assets
                Log.i(TAG, "Loading model from assets: $assetModelPath")
                val assetFd = context.assets.openFd(assetModelPath)
                val inputStream = FileInputStream(assetFd.fileDescriptor)
                val channel = inputStream.channel
                channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
            } else {
                Log.e(TAG, "No model file or asset path specified")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            null
        }
    }
}

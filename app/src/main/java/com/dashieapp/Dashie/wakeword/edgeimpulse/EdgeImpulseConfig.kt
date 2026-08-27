package com.dashieapp.Dashie.wakeword.edgeimpulse

/**
 * Edge Impulse Model Configuration
 *
 * ALL VALUES EXTRACTED FROM EDGE IMPULSE C++ SDK
 * Source files:
 * - model-parameters/model_metadata.h
 * - model-parameters/model_variables.h
 *
 * DO NOT MODIFY - These are hardcoded in the model
 */
object EdgeImpulseConfig {
    // Model metadata
    const val PROJECT_ID = 836170
    const val PROJECT_NAME = "hey-dashie-wakeword-v1"
    const val DEPLOY_VERSION = 3

    // Audio input
    const val SAMPLE_RATE = 16000                // Hz
    const val WINDOW_SIZE_SAMPLES = 16000        // 1 second of audio

    // MFE Feature Extraction (from model_variables.h lines 57-71)
    const val IMPLEMENTATION_VERSION = 4         // Version 4 algorithm
    const val FRAME_LENGTH_MS = 20.0f            // 20ms frames
    const val FRAME_STRIDE_MS = 10.0f            // 10ms stride (50% overlap)
    const val NUM_FILTERS = 40                   // Mel filters (REQUIRED by model)
    const val FFT_LENGTH = 256                   // FFT size (REQUIRED by model)
    const val LOW_FREQ_HZ = 0                    // Low frequency (Version 4: 0 Hz)
    const val HIGH_FREQ_HZ = 8000                // High frequency (Nyquist)
    const val WIN_SIZE = 101                     // Frames per window
    const val NOISE_FLOOR_DB = -52               // Noise floor threshold

    // Preemphasis (Version 4 requirement)
    const val PREEMPHASIS_COEFF = 0.98f          // y[n] = x[n] - 0.98*x[n-1]

    // Model input/output
    const val NN_INPUT_SIZE = 3960               // 99 frames × 40 filters
    const val NN_OUTPUT_COUNT = 2                // Number of classes
    val LABELS = arrayOf("hey_dashie", "unknown")

    // Detection threshold (default value, can be changed at runtime via setThreshold)
    const val DEFAULT_DETECTION_THRESHOLD = 0.80f  // Default confidence threshold (0.0-1.0)
    var DETECTION_THRESHOLD = DEFAULT_DETECTION_THRESHOLD  // Runtime-configurable threshold

    // Quantization (from model_variables.h lines 121-124)
    const val OUTPUT_ZERO_POINT = -128           // INT8 output zero point
    const val OUTPUT_SCALE = 0.00390625f         // Output scale (1/256)
}

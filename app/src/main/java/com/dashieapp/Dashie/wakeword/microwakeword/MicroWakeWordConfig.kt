package com.dashieapp.Dashie.wakeword.microwakeword

/**
 * Configuration for microWakeWord detection, matching ESPHome defaults.
 *
 * These values must match the training pipeline and ESPHome component:
 * https://github.com/esphome/esphome/blob/dev/esphome/components/micro_wake_word/
 */
object MicroWakeWordConfig {

    // Audio
    const val SAMPLE_RATE = 16000
    const val SAMPLES_PER_CHUNK = 160  // 10ms at 16kHz

    // Feature extraction (handled by native MicroFrontend)
    const val FEATURE_SIZE = 40        // Mel filterbank bins
    const val FEATURE_STEP_SIZE_MS = 10  // Default step size
    const val STRIDE_FRAMES = 3        // Frames per inference (30ms = 3 * 10ms)

    // Model input/output
    const val INPUT_FRAMES = 3         // Matches STRIDE_FRAMES
    const val INPUT_FEATURES = 40      // Matches FEATURE_SIZE
    // Input tensor shape: [1, 3, 40]

    // Detection thresholds (runtime-configurable per model)
    var probabilityCutoff = 0.85f      // Average probability to trigger
    var slidingWindowSize = 5          // Number of probabilities to average

    // Cooldown
    const val COOLDOWN_MS = 1500L      // 1.5 seconds between detections

    // Detection loop timing
    const val POLL_INTERVAL_MS = 10L   // 10ms per chunk (matching feature step size)

    // Heartbeat
    const val HEARTBEAT_INTERVAL_CHUNKS = 50  // Every 500ms (50 * 10ms)

    // Labels
    const val LABEL_WAKE_WORD = "wake_word"
}

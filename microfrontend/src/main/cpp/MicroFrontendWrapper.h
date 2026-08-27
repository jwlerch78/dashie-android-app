// SPDX-License-Identifier: Apache-2.0
// Based on Home Assistant Android app microfrontend module

#pragma once

#include <vector>
#include <cstdint>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
}

/**
 * C++ wrapper for TFLite Micro Frontend audio feature extraction.
 *
 * Configures the frontend to match ESPHome microWakeWord preprocessor settings:
 * - 40 mel filterbank bins, 125-7500 Hz
 * - 30ms window, configurable step size
 * - Noise reduction + PCAN gain control
 *
 * Non-copyable due to internal state.
 */
class MicroFrontendWrapper {
public:
    MicroFrontendWrapper(int sampleRate, size_t stepSizeMs);
    virtual ~MicroFrontendWrapper();

    // Non-copyable
    MicroFrontendWrapper(const MicroFrontendWrapper&) = delete;
    MicroFrontendWrapper& operator=(const MicroFrontendWrapper&) = delete;

    bool isInitialized() const { return initialized_; }

    /**
     * Process 16-bit PCM audio samples and extract spectrogram features.
     * @param samples Audio samples at the configured sample rate
     * @param numSamples Number of samples
     * @return List of feature frames, each containing 40 mel filterbank float values
     */
    std::vector<std::vector<float>> processSamples(const int16_t* samples, size_t numSamples);

    /** Reset internal state (noise estimates, PCAN state, sample buffer). */
    void reset();

private:
    int sampleRate_;
    size_t stepSizeMs_;
    bool initialized_ = false;
    struct FrontendState state_;
};

cmake_minimum_required(VERSION 3.22)
project(microfrontend)

include(FetchContent)

# ── Optimization flags ──────────────────────────────────────────────
add_compile_options(-O3 -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections)

# ── Dependencies ────────────────────────────────────────────────────

# KissFFT - Fast Fourier Transform library
# Use Populate (not MakeAvailable) to skip KissFFT's own CMakeLists.txt
# which requires pkg-config (unavailable in NDK cross-compilation)
FetchContent_Declare(
    kissfft
    GIT_REPOSITORY https://github.com/mborgerding/kissfft.git
    GIT_TAG        131.2.0
)
FetchContent_Populate(kissfft)

# Copy KissFFT files to the directory structure expected by TFLite Micro Frontend.
# TFLite includes: "kissfft/kiss_fft.h" and "tools/kiss_fftr.h" (both relative to kissfft_SOURCE_DIR)
# KissFFT v131.2.0 has kiss_fftr.h at root, not in tools/
file(MAKE_DIRECTORY ${kissfft_SOURCE_DIR}/kissfft)
file(MAKE_DIRECTORY ${kissfft_SOURCE_DIR}/kissfft/tools)
file(COPY ${kissfft_SOURCE_DIR}/kiss_fft.h DESTINATION ${kissfft_SOURCE_DIR}/kissfft/)
file(COPY ${kissfft_SOURCE_DIR}/kiss_fft.c DESTINATION ${kissfft_SOURCE_DIR}/kissfft/)
file(COPY ${kissfft_SOURCE_DIR}/_kiss_fft_guts.h DESTINATION ${kissfft_SOURCE_DIR}/kissfft/)
file(COPY ${kissfft_SOURCE_DIR}/kiss_fftr.h DESTINATION ${kissfft_SOURCE_DIR}/kissfft/tools/)
file(COPY ${kissfft_SOURCE_DIR}/kiss_fftr.c DESTINATION ${kissfft_SOURCE_DIR}/kissfft/tools/)
# Also copy to tools/ at root level — TFLite's kiss_fft_int16.h includes "tools/kiss_fftr.h"
file(COPY ${kissfft_SOURCE_DIR}/kiss_fftr.h DESTINATION ${kissfft_SOURCE_DIR}/tools/)
file(COPY ${kissfft_SOURCE_DIR}/kiss_fftr.c DESTINATION ${kissfft_SOURCE_DIR}/tools/)

# TFLite Micro - only the frontend sources
# Use Populate to avoid building the full tflite-micro project
FetchContent_Declare(
    tflite_micro
    GIT_REPOSITORY https://github.com/tensorflow/tflite-micro.git
    GIT_TAG        2747abd5c82a95fb1624106a946fc671c31f16e8
)
FetchContent_Populate(tflite_micro)

# ── TFLite Micro Frontend static library ────────────────────────────
set(TFLITE_MICRO_FE_DIR ${tflite_micro_SOURCE_DIR}/tensorflow/lite/experimental/microfrontend/lib)

add_library(tflite_micro_frontend STATIC
    # KissFFT sources (needed by fft.cc / fft_util.cc)
    ${kissfft_SOURCE_DIR}/kiss_fft.c
    ${kissfft_SOURCE_DIR}/kiss_fftr.c
    ${TFLITE_MICRO_FE_DIR}/fft.cc
    ${TFLITE_MICRO_FE_DIR}/fft_util.cc
    ${TFLITE_MICRO_FE_DIR}/filterbank.c
    ${TFLITE_MICRO_FE_DIR}/filterbank_util.c
    ${TFLITE_MICRO_FE_DIR}/frontend.c
    ${TFLITE_MICRO_FE_DIR}/frontend_util.c
    ${TFLITE_MICRO_FE_DIR}/log_lut.c
    ${TFLITE_MICRO_FE_DIR}/log_scale.c
    ${TFLITE_MICRO_FE_DIR}/log_scale_util.c
    ${TFLITE_MICRO_FE_DIR}/noise_reduction.c
    ${TFLITE_MICRO_FE_DIR}/noise_reduction_util.c
    ${TFLITE_MICRO_FE_DIR}/pcan_gain_control.c
    ${TFLITE_MICRO_FE_DIR}/pcan_gain_control_util.c
    ${TFLITE_MICRO_FE_DIR}/window.c
    ${TFLITE_MICRO_FE_DIR}/window_util.c
)

target_include_directories(tflite_micro_frontend PUBLIC
    ${tflite_micro_SOURCE_DIR}
    ${kissfft_SOURCE_DIR}
)

# Use fixed-point 16-bit arithmetic (must match model training)
target_compile_definitions(tflite_micro_frontend PUBLIC FIXED_POINT=16)

target_compile_options(tflite_micro_frontend PRIVATE
    -Wno-sign-compare
    -Wno-unused-parameter
    -Wno-missing-field-initializers
    -ffast-math
)

# ── JNI shared library ──────────────────────────────────────────────
add_library(microfrontend SHARED
    MicroFrontend_jni.cpp
    MicroFrontendWrapper.cpp
)

target_link_libraries(microfrontend
    tflite_micro_frontend
    android
    log
)

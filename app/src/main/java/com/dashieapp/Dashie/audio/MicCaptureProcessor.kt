package com.dashieapp.Dashie.audio

/**
 * Optional in-line hook on [AudioCaptureService]'s read loop — the single seam where
 * every mic sample passes before reaching [SharedAudioBuffer] (and from there both the
 * wake-word detector and STT). Used by the cascade AEC (CascadeAecController) to
 * subtract Dashie's own TTS from the mic once, for all consumers.
 *
 * Contract (called on the capture thread, ~100 ms cadence):
 *  - return the processed samples to write (length may differ from [count] — the
 *    processor may hold a sub-frame remainder and release it with a later chunk),
 *  - return an empty array to write nothing this chunk (remainder held),
 *  - return null to pass the raw chunk through untouched (processor inactive).
 * Must never throw into the audio path; [AudioCaptureService] treats a throw as null.
 */
fun interface MicCaptureProcessor {
    fun process(samples: ShortArray, count: Int): ShortArray?
}

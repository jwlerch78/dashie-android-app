package com.dashieapp.Dashie.voice.realtime

/**
 * Common surface for a software echo canceller used during conversation mode.
 *
 * Two implementations behind this seam (build plan 20260627_REALTIME_VOICE_AEC):
 *  • [RealtimeAec3] — WebRTC AEC3 (full-band, double-talk friendly). Primary.
 *  • [RealtimeAec]  — WebRTC AECM (fixed-point, low-RAM). Fallback when AEC3 can't
 *    initialise (e.g. its native lib fails to load on some ABI/device).
 *
 * [RealtimeAudioIo] feeds far-end playback to [processReverse] and runs mic frames
 * through [processCapture], without caring which canceller is active.
 */
interface SoftwareAec {
    /** Feed far-end playback PCM (mono PCM16, little-endian) as the echo reference. */
    fun processReverse(pcm: ByteArray)

    /** Echo-cancel near-end mic PCM (mono PCM16, little-endian). Returns the cleaned
     *  bytes, an empty array if only a sub-frame remainder was buffered, or null if the
     *  canceller isn't active (caller passes the raw frame through). */
    fun processCapture(pcm: ByteArray): ByteArray?

    /** Release native resources. */
    fun release()
}

package com.dashieapp.Dashie.halite.voice.speakerid

import android.content.Context

/**
 * Speaker-embedding inference — STUB (prod/firetv/amazon/sideload/release).
 *
 * These flavors ship neither the ONNX Runtime dependency nor the 25 MB
 * model asset (BuildConfig.SPEAKER_ID_ENABLED is false); this stub keeps
 * shared code compiling. Keep the public surface identical to
 * src/speakeridImpl/.../SpeakerEmbedder.kt.
 */
@Suppress("UNUSED_PARAMETER")
class SpeakerEmbedder private constructor() {
    fun close() {}

    fun embed(pcm: ShortArray): FloatArray? = null

    companion object {
        const val EMBEDDING_DIM = 256
        const val SAMPLE_RATE = KaldiFbank.SAMPLE_RATE
        const val MIN_SAMPLES = (SAMPLE_RATE * 1.2).toInt()

        fun isAvailable(context: Context): Boolean = false

        fun create(context: Context): SpeakerEmbedder? = null
    }
}

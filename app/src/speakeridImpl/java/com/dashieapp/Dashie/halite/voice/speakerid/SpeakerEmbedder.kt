package com.dashieapp.Dashie.halite.voice.speakerid

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker-embedding inference — REAL implementation (local/staging flavors).
 *
 * WeSpeaker ResNet34-LM ONNX (256-dim, chosen 2026-07-10 — see
 * wake-word-training/speaker-id/results/LIBRI_DEV_BASELINE.md). Input is the
 * KaldiFbank features; output is an L2-normalized voiceprint vector.
 * Same-speaker vectors have high cosine similarity.
 *
 * A same-API stub exists in src/speakeridStub for all other flavors; keep
 * the two public surfaces identical. The model asset is fetched by
 * scripts/fetch-speakerid-model.sh and is absent from git — create()
 * returns null when the asset (or flavor gate) is missing, and callers
 * treat null as feature-unavailable.
 */
class SpeakerEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    fun close() {
        runCatching { session.close() }
    }

    /**
     * PCM16 @16 kHz mono -> L2-normalized 256-dim embedding, or null when
     * the clip is too short (< MIN_SAMPLES) to embed meaningfully.
     */
    fun embed(pcm: ShortArray): FloatArray? {
        if (pcm.size < MIN_SAMPLES) return null
        val feats = KaldiFbank.extract(pcm, applyCmn = true)
        if (feats.isEmpty()) return null

        val frames = feats.size
        val flat = FloatArray(frames * KaldiFbank.NUM_BINS)
        for (f in 0 until frames) {
            System.arraycopy(feats[f], 0, flat, f * KaldiFbank.NUM_BINS, KaldiFbank.NUM_BINS)
        }
        val shape = longArrayOf(1, frames.toLong(), KaldiFbank.NUM_BINS.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
            session.run(mapOf(INPUT_NAME to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = (result[0].value as Array<FloatArray>)[0]
                var norm = 0.0
                for (v in raw) norm += (v * v).toDouble()
                val inv = if (norm > 0) (1.0 / sqrt(norm)).toFloat() else 0f
                return FloatArray(raw.size) { raw[it] * inv }
            }
        }
    }

    companion object {
        private const val TAG = "SpeakerEmbedder"
        const val EMBEDDING_DIM = 256
        const val SAMPLE_RATE = KaldiFbank.SAMPLE_RATE

        /** ~1.2 s minimum — below this, embeddings are unreliable. */
        const val MIN_SAMPLES = (SAMPLE_RATE * 1.2).toInt()

        private const val ASSET_PATH = "models/speakerid/wespeaker_resnet34_lm.onnx"
        private const val INPUT_NAME = "feats"

        fun isAvailable(context: Context): Boolean =
            runCatching { context.assets.open(ASSET_PATH).use { } }.isSuccess

        /**
         * Null when the model asset is missing (fetch script not run) —
         * callers must treat null as feature-unavailable, never crash.
         */
        fun create(context: Context): SpeakerEmbedder? {
            val bytes = try {
                context.assets.open(ASSET_PATH).use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "Speaker-ID model asset missing ($ASSET_PATH) — " +
                        "run scripts/fetch-speakerid-model.sh; feature disabled")
                return null
            }
            return try {
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(bytes)
                Log.i(TAG, "Speaker-ID model loaded (${bytes.size / 1024 / 1024} MB)")
                SpeakerEmbedder(env, session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init ONNX session for speaker-ID", e)
                null
            }
        }
    }
}

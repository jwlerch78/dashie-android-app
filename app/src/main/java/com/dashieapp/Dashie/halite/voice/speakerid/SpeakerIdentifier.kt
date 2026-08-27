package com.dashieapp.Dashie.halite.voice.speakerid

/**
 * The household speaker decision layer: scores an utterance embedding
 * against every member's voiceprint vector set and maps the best score to
 * the tri-state UX (KNOWN / UNCERTAIN / UNKNOWN).
 *
 * Score = max cosine similarity over the member's vectors (embeddings are
 * L2-normalized, so cosine = dot product). Max-over-set beats a single
 * centroid for intra-speaker variability (distance, morning voice) — the
 * Apple multi-vector design.
 *
 * Initial thresholds derive from the LibriSpeech baseline sweep
 * (wake-word-training/speaker-id/results/LIBRI_DEV_BASELINE.md) and MUST be
 * re-derived from real household enrollment data — the benchmark harness
 * replays logged vectors offline. Keep every threshold change mirrored in
 * that doc.
 *
 * NOT security: this gates personalization/convenience only. At these
 * thresholds a small fraction of guests can score as a member.
 */
class SpeakerIdentifier(
    private val store: VoiceprintStore,
    private val knownThreshold: Float = DEFAULT_T_KNOWN,
    private val uncertainThreshold: Float = DEFAULT_T_UNCERTAIN,
) {

    enum class Status { KNOWN, UNCERTAIN, UNKNOWN, NO_PROFILES }

    data class Result(
        val status: Status,
        val memberId: String?,
        val memberName: String?,
        val score: Float,
        /** Runner-up, for telemetry + sibling-confusability monitoring. */
        val secondMemberId: String?,
        val secondScore: Float,
    ) {
        companion object {
            val NO_PROFILES = Result(Status.NO_PROFILES, null, null, 0f, null, 0f)
        }
    }

    fun identify(embedding: FloatArray): Result {
        val profiles = store.getProfiles().values.filter { it.vectors.isNotEmpty() }
        if (profiles.isEmpty()) return Result.NO_PROFILES

        var bestId: String? = null
        var bestName: String? = null
        var bestScore = -1f
        var secondId: String? = null
        var secondScore = -1f

        for (profile in profiles) {
            var memberScore = -1f
            for (v in profile.vectors) {
                val s = dot(embedding, v.vector)
                if (s > memberScore) memberScore = s
            }
            if (memberScore > bestScore) {
                secondId = bestId
                secondScore = bestScore
                bestId = profile.memberId
                bestName = profile.memberName
                bestScore = memberScore
            } else if (memberScore > secondScore) {
                secondId = profile.memberId
                secondScore = memberScore
            }
        }

        val status = when {
            bestScore >= knownThreshold -> Status.KNOWN
            bestScore >= uncertainThreshold -> Status.UNCERTAIN
            else -> Status.UNKNOWN
        }
        return Result(
            status = status,
            memberId = if (status != Status.UNKNOWN) bestId else null,
            memberName = if (status != Status.UNKNOWN) bestName else null,
            score = bestScore,
            secondMemberId = secondId,
            secondScore = secondScore,
        )
    }

    companion object {
        // From the LibriSpeech dev-clean sweep (clean speech); re-derive on
        // household data. Applied to max-over-vector-set scores.
        const val DEFAULT_T_KNOWN = 0.40f
        const val DEFAULT_T_UNCERTAIN = 0.25f

        fun dot(a: FloatArray, b: FloatArray): Float {
            var acc = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) acc += a[i] * b[i]
            return acc
        }
    }
}

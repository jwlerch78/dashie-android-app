package com.dashieapp.Dashie.halite.schedule.condition

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Resolves a spoken phrase ("hot tub", "the pool thermometer") to an HA
 * entity via REST /api/states (EntityPickerFragment pattern) with token
 * scoring over friendly names. Numeric-state entities are strongly
 * preferred since v1 conditions are numeric comparisons.
 *
 * Blocking — call off the main thread. Credentials arrive per-call so the
 * HaTokenExtractor refresh path handles OAuth expiry.
 */
class HaEntityResolver(
    private val credentialsProvider: () -> Pair<String, String>?, // (baseUrl, token)
) {
    data class ResolvedEntity(
        val entityId: String,
        val friendlyName: String,
        val currentValue: Double?,
        val unit: String,
        val score: Int,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun resolve(phrase: String): ResolvedEntity? {
        val states = fetchStates() ?: return null
        val tokens = tokenize(phrase)
        if (tokens.isEmpty()) return null

        var best: ResolvedEntity? = null
        for (i in 0 until states.length()) {
            val o = states.optJSONObject(i) ?: continue
            val entityId = o.optString("entity_id")
            val attrs = o.optJSONObject("attributes")
            val friendly = attrs?.optString("friendly_name")?.takeIf { it.isNotEmpty() }
                ?: entityId.substringAfter('.').replace('_', ' ')
            val numeric = o.optString("state").toDoubleOrNull()

            var score = scoreTokens(tokens, tokenize(friendly) + tokenize(entityId.substringAfter('.')))
            if (score == 0) continue
            if (numeric != null) score += 2 // numeric-state preference
            if (entityId.startsWith("sensor.") || entityId.startsWith("number.") ||
                entityId.startsWith("climate.") || entityId.startsWith("input_number.")
            ) score += 1

            if (score > (best?.score ?: MIN_SCORE - 1)) {
                best = ResolvedEntity(
                    entityId = entityId,
                    friendlyName = friendly,
                    currentValue = numeric,
                    unit = attrs?.optString("unit_of_measurement") ?: "",
                    score = score,
                )
            }
        }
        Log.i(TAG, "resolve('$phrase') -> ${best?.entityId} (score ${best?.score})")
        return best
    }

    /** Current numeric state of one entity, or null (offline / non-numeric). */
    fun currentValue(entityId: String): Double? {
        val (baseUrl, token) = credentialsProvider() ?: return null
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/states/$entityId")
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                org.json.JSONObject(body).optString("state").toDoubleOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "currentValue($entityId) failed: ${e.message}")
            null
        }
    }

    private fun fetchStates(): JSONArray? {
        val (baseUrl, token) = credentialsProvider() ?: run {
            Log.w(TAG, "No HA credentials — cannot resolve entities")
            return null
        }
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/states")
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONArray(resp.body?.string() ?: return null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchStates failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "HaEntityResolver"
        private const val MIN_SCORE = 3 // ≥1 token hit (2) + numeric/domain bonus

        private val STOP_WORDS = setOf("the", "a", "an", "my", "our", "is", "at", "of")

        fun tokenize(s: String): List<String> =
            s.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 1 && it !in STOP_WORDS }

        /** 2 points per phrase token found in the candidate's tokens. */
        fun scoreTokens(phraseTokens: List<String>, candidateTokens: List<String>): Int {
            var score = 0
            for (t in phraseTokens) {
                if (candidateTokens.any { it == t || it.startsWith(t) || t.startsWith(it) }) {
                    score += 2
                }
            }
            // All-token match bonus: "hot tub" fully matching beats partials
            if (score == phraseTokens.size * 2 && phraseTokens.size > 1) score += 2
            return score
        }
    }
}

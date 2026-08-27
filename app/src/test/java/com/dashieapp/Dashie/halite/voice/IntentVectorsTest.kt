package com.dashieapp.Dashie.halite.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden BEHAVIORAL vectors for [KotlinIntentPatterns] — the Kotlin half of the shared corpus.
 *
 * Loads the SAME intent-vectors.json the JS Deno runner asserts (synced from the canonical at
 * dashieapp_staging/js/vendor/dashie-shared/intent-classifier/test-vectors/intent-vectors.json by
 * scripts/gen-intent-vectors.mjs) and asserts the `kotlin` column. This gates BEHAVIOR, not just
 * the shape the codegen gates cover: a change to parseDuration/normalizeWordNumbers LOGIC that
 * isn't reflected in the corpus fails here; a legitimate new divergence must be written into the
 * corpus (per-platform `expect`) — forcing every difference to be reviewed.
 *
 * Robolectric is required only because the classifier calls android.util.Log; the logic is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentVectorsTest {

    private val P = KotlinIntentPatterns

    private fun corpusCases(): JSONObject {
        val text = javaClass.classLoader!!
            .getResourceAsStream("intent-vectors.json")!!
            .bufferedReader().readText()
        return JSONObject(text)
    }

    /** Expected for THIS platform: a bare value = both agree; a { js, kotlin } object = a
     *  documented per-platform divergence — take the kotlin column. */
    private fun wantKotlin(expect: Any?): Any? {
        if (expect is JSONObject && (expect.has("js") || expect.has("kotlin"))) {
            return if (expect.isNull("kotlin")) null else expect.get("kotlin")
        }
        return if (expect === JSONObject.NULL) null else expect
    }

    private fun norm(v: Any?): Any? = (v as? Number)?.toInt() ?: v

    /** Camera-playback time parsing: expect = seconds BEFORE options.now. `now` is parsed as
     *  LOCAL wall-clock (same as the JS runner's `new Date("…T…")`) so offsets are
     *  zone-portable. Normalization mirrors the real call sites. */
    private fun timeReferenceOffset(input: String, options: JSONObject?): Int? {
        val nowStr = options?.optString("now").orEmpty()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(nowStr)!!
        val n = P.normalizeWordNumbers(input.lowercase().trim())
        return TimeReference.parse(n, now)?.let { (now.time / 1000 - it.timestampSec).toInt() }
    }

    /** The Kotlin transport-command names ARE the shared vector vocabulary (next/previous);
     *  the JS runner aliases its next_track/previous_track to match. */
    private fun mediaCommand(input: String, options: JSONObject?): String? {
        val musicPlaying = options?.optBoolean("musicPlaying", false) ?: false
        val r = P.classify(input, musicPlaying = musicPlaying) ?: return null
        return "${r.category}:${r.command}"
    }

    @Test
    fun `golden vectors match Kotlin behavior`() {
        val cases = corpusCases().getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val fn = c.getString("fn")
            val input = c.getString("input")
            val options = c.optJSONObject("options")
            val want = norm(wantKotlin(if (c.isNull("expect")) JSONObject.NULL else c.get("expect")))
            val actual: Any? = norm(
                when (fn) {
                    "parseDuration" -> P.parseDuration(input)?.totalSeconds
                    "normalizeWordNumbers" -> P.normalizeWordNumbers(input)
                    "mediaCommand" -> mediaCommand(input, options)
                    "hasSchedulingQualifier" -> HaEntityMatcher.hasSchedulingQualifier(input)
                    "hasSecondToolVerb" -> HaEntityMatcher.hasSecondToolVerb(input)
                    "timeReferenceOffset" -> timeReferenceOffset(input, options)
                    "templateWeather" -> WeatherTemplate.templateWeather(
                        options?.optJSONObject("data"),
                        JSONObject().put("timeframe", input)
                    )
                    else -> throw IllegalStateException("no Kotlin runner for fn '$fn'")
                }
            )
            if (want != actual) failures.add("  $fn(\"$input\") = ${q(actual)}, want ${q(want)}")
        }
        assertEquals(
            "golden-vector mismatch(es) — reconcile the logic or record the divergence in intent-vectors.json:\n" +
                failures.joinToString("\n"),
            0, failures.size
        )
    }

    private fun q(v: Any?): String = if (v is String) "\"$v\"" else v.toString()
}

package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply-lane TTS decision.
 *
 * Two defects and one hole are pinned here.
 *
 * (a) **`voice.ttsProvider` was ignored on the HA-Assist lane.** The cascade grew a router;
 *     HA-Assist called the device engine directly, so a user who picked Piper got the Android
 *     voice — with zero attempts and zero warnings, because the setting was never consulted
 *     rather than failing over. Found by ear (2026-08-26), invisible in every log.
 *
 * (b) **The confirmation-tone gate sat BELOW the branch**, inside the device path, where the
 *     router's own device lambda additionally hardcoded `isCommand = false`.
 *
 * (c) 🔴 **The billing hole the obvious fix opens.** `ttsProvider` and the WS-D.1 degraded
 *     override are the same decision. Route a lane through the router carrying the setting but
 *     NOT the override and it can speak with a billed cloud voice while the account is at $0.
 *     `a degraded override beats a configured cloud voice` is the test that fails if anyone
 *     ever separates them again — that is its whole job.
 */
class TtsRoutePlanTest {

    /** Everything configured, so a route is decided by the PREFERENCE, not by availability. */
    private fun resolve(
        configured: String,
        override: String? = null,
        haEngineConfigured: Boolean = true,
        localTtsUrlConfigured: Boolean = true,
        haPipelineAvailable: Boolean = true,
    ) = TtsRoutePlan.resolve(
        configuredProvider = configured,
        degradedOverride = override,
        haEngineConfigured = haEngineConfigured,
        localTtsUrlConfigured = localTtsUrlConfigured,
        haPipelineAvailable = haPipelineAvailable,
    )

    // ── (a) the reported defect ───────────────────────────────────────────────

    @Test
    fun `a piper choice routes to the HA engine, not the device`() {
        val route = resolve(VoicePreferences.TTS_HA_ENGINE)
        assertEquals("the defect: this resolved to DEVICE on the HA-Assist lane",
            TtsRoutePlan.Engine.HA_ENGINE, route.engine)
        assertNull("an honoured choice is not a fallback", route.unconfigured)
        assertFalse(route.substituted)
    }

    @Test
    fun `each provider id routes to its own engine`() {
        assertEquals(TtsRoutePlan.Engine.CLOUD, resolve(VoicePreferences.TTS_DASHIE_CLOUD).engine)
        assertEquals(TtsRoutePlan.Engine.LOCAL_URL, resolve(VoicePreferences.TTS_LOCAL_URL).engine)
        assertEquals(TtsRoutePlan.Engine.HA_PIPELINE, resolve(VoicePreferences.TTS_VA_DEFAULT).engine)
        assertEquals(TtsRoutePlan.Engine.DEVICE, resolve(VoicePreferences.TTS_ANDROID_VOICE).engine)
    }

    @Test
    fun `an unrecognised provider id speaks on the device rather than dropping the reply`() {
        val route = resolve("some_engine_from_a_newer_build")
        assertEquals(TtsRoutePlan.Engine.DEVICE, route.engine)
        assertNull("not a fallback — the device engine IS the default", route.unconfigured)
    }

    // ── (c) the billing hole ──────────────────────────────────────────────────

    @Test
    fun `a degraded override beats a configured cloud voice`() {
        // 🔴 If this test fails, an out-of-credits account is being billed for TTS.
        val route = resolve(
            configured = VoicePreferences.TTS_DASHIE_CLOUD,
            override = VoicePreferences.TTS_ANDROID_VOICE,
        )
        assertEquals("at \$0 the free engine speaks", TtsRoutePlan.Engine.DEVICE, route.engine)
        assertEquals(VoicePreferences.TTS_ANDROID_VOICE, route.resolvedProvider)
        assertEquals("the user's setting is never mutated, only overridden",
            VoicePreferences.TTS_DASHIE_CLOUD, route.configuredProvider)
        assertTrue("a log must be able to say this was substituted", route.substituted)
    }

    @Test
    fun `an override equal to the setting is not a substitution`() {
        // Degrading a user who already picked the free engine changes nothing, and a log that
        // called it a substitution would send a debugger hunting a dishonoured preference.
        val route = resolve(
            configured = VoicePreferences.TTS_ANDROID_VOICE,
            override = VoicePreferences.TTS_ANDROID_VOICE,
        )
        assertFalse(route.substituted)
    }

    @Test
    fun `no override honours the setting`() {
        val route = resolve(VoicePreferences.TTS_DASHIE_CLOUD, override = null)
        assertEquals(TtsRoutePlan.Engine.CLOUD, route.engine)
        assertFalse(route.substituted)
    }

    // ── unconfigured engines fall back, and say so ────────────────────────────

    @Test
    fun `ha_engine with nothing configured falls back to the device and names why`() {
        val route = resolve(VoicePreferences.TTS_HA_ENGINE, haEngineConfigured = false)
        assertEquals(TtsRoutePlan.Engine.DEVICE, route.engine)
        assertEquals("a silent fallback here is indistinguishable from the defect this fixes",
            TtsRoutePlan.Engine.HA_ENGINE, route.unconfigured)
    }

    @Test
    fun `local_url with no url falls back to the device and names why`() {
        val route = resolve(VoicePreferences.TTS_LOCAL_URL, localTtsUrlConfigured = false)
        assertEquals(TtsRoutePlan.Engine.DEVICE, route.engine)
        assertEquals(TtsRoutePlan.Engine.LOCAL_URL, route.unconfigured)
    }

    @Test
    fun `va_default on a lane with no pipeline synthesizer speaks on the device`() {
        // This is the HA-Assist lane: HaTtsSynthesizer is built by initializeVoicePipeline, so
        // it does not exist there. Reached only when the pipeline itself can't do TTS and the
        // planner coerced the reply to the device (HaAssistStagePlanner.coercedToLocalTts) —
        // otherwise HA speaks its own voice and this router is never asked.
        val route = resolve(VoicePreferences.TTS_VA_DEFAULT, haPipelineAvailable = false)
        assertEquals(TtsRoutePlan.Engine.DEVICE, route.engine)
        assertEquals(TtsRoutePlan.Engine.HA_PIPELINE, route.unconfigured)
    }

    // ── (b) the tone gate, now above the branch ───────────────────────────────

    @Test
    fun `a command ack beeps when a tone is selected`() {
        assertTrue(TtsRoutePlan.speaksToneInsteadOfWords(isCommand = true, confirmationToneEnabled = true))
    }

    @Test
    fun `a command ack is spoken when no tone is selected`() {
        // "disabled" must never swallow BOTH the tone and the speech.
        assertFalse(TtsRoutePlan.speaksToneInsteadOfWords(isCommand = true, confirmationToneEnabled = false))
    }

    @Test
    fun `an answer is spoken even with a tone selected`() {
        // The gate applies to acknowledgements only — an informational answer beeped instead of
        // spoken is the feature silently eating the user's question.
        assertFalse(TtsRoutePlan.speaksToneInsteadOfWords(isCommand = false, confirmationToneEnabled = true))
    }

    // ── the log line (#64) — the oracle for "is the setting honoured?" ────────
    //
    // 🔒 These pin Thread T's pre-agreed MARKER CONTRACT (s47 cont.1). The caller renders
    // `TTS = device (<describe>)`, and T's leg greps FIXED STRINGS: the setting value must come
    // FIRST (so the prefix `TTS = device (ha_engine` matches), plus the bare token `SUBSTITUTED`
    // and `user picked <setting value>`. Everything after the setting value is decorative and
    // may be enriched freely. Breaking one of these is a breaking change to T's instrument, so
    // it is pinned here rather than left to goodwill — `npm run lint:markers` catches the rest
    // statically, but only what T has already pinned.

    @Test
    fun `describe leads with the setting value, then the concrete engine`() {
        val line = TtsRoutePlan.describe(
            route = resolve(VoicePreferences.TTS_HA_ENGINE),
            haEngineId = "tts.piper", haVoiceId = "en_US-danny-low",
            localTtsUrl = "", deviceEngineId = "com.google.android.tts",
        )
        assertTrue("T greps the prefix `TTS = device (ha_engine` as a fixed string",
            line.startsWith(VoicePreferences.TTS_HA_ENGINE))
        assertTrue("the concrete engine catches defect 63's second failure mode — " +
            "ha_engine honoured but haTtsEngineId unset or wrong", line.contains("tts.piper"))
        assertTrue("…and which voice it will speak in", line.contains("en_US-danny-low"))
    }

    @Test
    fun `describe names the android engine package on the device route`() {
        val line = TtsRoutePlan.describe(
            route = resolve(VoicePreferences.TTS_ANDROID_VOICE),
            haEngineId = "tts.piper", haVoiceId = "", localTtsUrl = "",
            deviceEngineId = "com.google.android.tts",
        )
        assertTrue(line, line.startsWith(VoicePreferences.TTS_ANDROID_VOICE))
        assertTrue(line, line.contains("com.google.android.tts"))
    }

    @Test
    fun `describe reports a degraded substitution with what the user picked`() {
        // 🔴 T's leg scores an ANNOUNCED contradiction as correct behaviour and a SILENT one as
        // a defect. Without these tokens the billing-hole guard is untestable by any leg.
        val line = TtsRoutePlan.describe(
            route = resolve(VoicePreferences.TTS_DASHIE_CLOUD, override = VoicePreferences.TTS_ANDROID_VOICE),
            haEngineId = "", haVoiceId = "", localTtsUrl = "", deviceEngineId = "com.google.android.tts",
        )
        assertTrue(line, line.startsWith(VoicePreferences.TTS_ANDROID_VOICE))
        assertTrue("free-engine degradation must not read as a dishonoured preference",
            line.contains("SUBSTITUTED"))
        assertTrue(line, line.contains("user picked " + VoicePreferences.TTS_DASHIE_CLOUD))
    }

    @Test
    fun `describe reports an unconfigured fallback as the engine that actually renders`() {
        val line = TtsRoutePlan.describe(
            route = resolve(VoicePreferences.TTS_HA_ENGINE, haEngineConfigured = false),
            haEngineId = "", haVoiceId = "", localTtsUrl = "", deviceEngineId = "com.google.android.tts",
        )
        assertTrue("never the raw preference — the engine that actually renders this turn",
            line.startsWith(VoicePreferences.TTS_ANDROID_VOICE))
        assertTrue("T's ANNOUNCE token, shared with the runtime failure paths",
            line.contains("falling back to device TTS"))
        assertTrue(line, line.contains(VoicePreferences.TTS_HA_ENGINE + " not configured"))
    }
}

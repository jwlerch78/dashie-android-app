package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage decision for one HA Voice Assist turn.
 *
 * Two bugs are pinned here. (a) A local STT choice was silently discarded: `ha_assist` always sent
 * audio for HA to transcribe, so `stt_provider=sherpa_moonshine_tiny` never ran. (b) A pipeline
 * that can't do the stage hard-failed the turn instead of degrading — `stt_engine: null` gave
 * "the pipeline does not support speech-to-text", and its TTS twin is documented in
 * LocalVoiceSwitch (2026-07-22).
 */
class HaAssistStagePlannerTest {

    private val canBoth = HaPipelineCapabilityCache.Caps("p1", "Dashie", hasStt = true, hasTts = true)
    private val canNeither = HaPipelineCapabilityCache.Caps("p0", "Home Assistant", hasStt = false, hasTts = false)
    private val sttOnly = HaPipelineCapabilityCache.Caps("p2", "Listener", hasStt = true, hasTts = false)

    @Test
    fun `a local stt choice is honored — HA starts at intent`() {
        val plan = HaAssistStagePlanner.plan(
            caps = canBoth, sttIsLocalByChoice = true, localSttAvailable = true,
            prefersLocalTts = true,
        )
        assertTrue("the whole point: transcribe on-device, hand HA text", plan.startAtIntent)
        assertTrue(plan.endAtIntent)
        assertNull(plan.error)
        assertFalse("an honored choice is not a coercion", plan.coercedToLocalStt)
    }

    @Test
    fun `a local stt choice with a capable pipeline still skips HA stt`() {
        // Even when HA *could* transcribe, the user's explicit choice wins.
        val plan = HaAssistStagePlanner.plan(
            caps = canBoth, sttIsLocalByChoice = true, localSttAvailable = true,
            prefersLocalTts = false,
        )
        assertTrue(plan.startAtIntent)
        assertFalse("HA keeps the TTS stage when the user wants the pipeline voice", plan.endAtIntent)
    }

    @Test
    fun `an stt-less pipeline degrades to local stt with a DROP flag`() {
        val plan = HaAssistStagePlanner.plan(
            caps = canNeither, sttIsLocalByChoice = false, localSttAvailable = true,
            prefersLocalTts = false,
        )
        assertTrue(plan.startAtIntent)
        assertTrue("caller must log the DROP: warn", plan.coercedToLocalStt)
        assertTrue("no tts_engine either → device speaks", plan.endAtIntent)
        assertTrue(plan.coercedToLocalTts)
        assertNull("degrading beats refusing", plan.error)
    }

    @Test
    fun `an stt-less pipeline with no local stt refuses with an actionable message`() {
        val plan = HaAssistStagePlanner.plan(
            caps = canNeither, sttIsLocalByChoice = false, localSttAvailable = false,
            prefersLocalTts = false,
        )
        assertNotNull(plan.error)
        assertTrue("must name the offending pipeline", plan.error!!.contains("Home Assistant"))
        assertTrue("must say where to fix it", plan.error.contains("Voice Pipeline"))
        assertFalse(plan.startAtIntent)
    }

    @Test
    fun `a tts-less pipeline forces the device voice without touching stt`() {
        val plan = HaAssistStagePlanner.plan(
            caps = sttOnly, sttIsLocalByChoice = false, localSttAvailable = true,
            prefersLocalTts = false,
        )
        assertFalse("HA can hear, so it keeps the STT stage", plan.startAtIntent)
        assertTrue("HA cannot speak, so end at intent", plan.endAtIntent)
        assertTrue(plan.coercedToLocalTts)
        assertNull(plan.error)
    }

    @Test
    fun `an unknown pipeline behaves exactly as before`() {
        // Cold cache: no blocking, no coercion — today's streaming path.
        val plan = HaAssistStagePlanner.plan(
            caps = null, sttIsLocalByChoice = false, localSttAvailable = true,
            prefersLocalTts = false,
        )
        assertFalse(plan.startAtIntent)
        assertFalse(plan.endAtIntent)
        assertNull(plan.error)
        assertFalse(plan.coercedToLocalStt)
        assertFalse(plan.coercedToLocalTts)
    }

    @Test
    fun `a local stt choice we cannot actually run falls back to HA stt`() {
        // sherpa selected on a build that doesn't bundle it, pipeline CAN hear → stream to HA.
        val plan = HaAssistStagePlanner.plan(
            caps = canBoth, sttIsLocalByChoice = true, localSttAvailable = false,
            prefersLocalTts = true,
        )
        assertFalse(plan.startAtIntent)
        assertNull(plan.error)
    }

    @Test
    fun `local tts preference is never overridden by a capable pipeline`() {
        val plan = HaAssistStagePlanner.plan(
            caps = canBoth, sttIsLocalByChoice = false, localSttAvailable = false,
            prefersLocalTts = true,
        )
        assertTrue(plan.endAtIntent)
        assertFalse("the user chose it, so it isn't a coercion", plan.coercedToLocalTts)
    }

    @Test
    fun `every path yields either a usable plan or an actionable error`() {
        val capsOptions = listOf(null, canBoth, canNeither, sttOnly)
        for (caps in capsOptions) for (localChoice in listOf(true, false))
            for (localAvail in listOf(true, false)) for (localTts in listOf(true, false)) {
                val plan = HaAssistStagePlanner.plan(caps, localChoice, localAvail, localTts)
                if (plan.error != null) {
                    assertFalse("a refused turn must not also claim a local stage", plan.startAtIntent)
                } else {
                    // A plan that starts at intent REQUIRES a local transcriber.
                    if (plan.startAtIntent) assertTrue(
                        "startAtIntent with no local STT would send HA an empty transcript",
                        localAvail,
                    )
                }
                assertEquals(
                    "coercedToLocalStt implies startAtIntent",
                    plan.coercedToLocalStt, plan.coercedToLocalStt && plan.startAtIntent,
                )
            }
    }
}

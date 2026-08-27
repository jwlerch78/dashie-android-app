package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the STT lead/fallback decision extracted from VoicePipelineCoordinator.initialize().
 *
 * The fallback CHAINS are the interesting part: each non-cloud lead keeps a path back to a
 * reachable provider, so a misconfigured local Whisper or an unreachable HA engine degrades
 * instead of leaving the device deaf.
 */
class SttProviderPriorityTest {

    /** Customized pipeline → the explicit STT choice is the lead. */
    private fun priorityFor(stt: String) = SttProviderFactory.resolvePriority(
        customizePipeline = true, sttProvider = stt, voiceControlMethod = "")

    @Test
    fun `dashie cloud leads with deepgram and flags cloud stt`() {
        val p = priorityFor(VoicePreferences.STT_DASHIE_CLOUD)
        assertEquals(listOf(ProviderType.DEEPGRAM, ProviderType.HA_ASSIST), p.order)
        assertTrue("only the cloud lead sets usingCloudStt", p.usingCloudStt)
    }

    @Test
    fun `va default leads with ha assist`() {
        val p = priorityFor(VoicePreferences.STT_VA_DEFAULT)
        assertEquals(listOf(ProviderType.HA_ASSIST, ProviderType.DEEPGRAM), p.order)
        assertFalse(p.usingCloudStt)
    }

    @Test
    fun `ha engine falls back through assist to cloud`() {
        val p = priorityFor(VoicePreferences.STT_HA_ENGINE)
        assertEquals(
            listOf(ProviderType.HA_ENGINE, ProviderType.HA_ASSIST, ProviderType.DEEPGRAM),
            p.order,
        )
    }

    @Test
    fun `local whisper falls back to cloud then ha`() {
        val p = priorityFor(VoicePreferences.STT_LOCAL_URL)
        assertEquals(
            listOf(ProviderType.LOCAL_WHISPER, ProviderType.DEEPGRAM, ProviderType.HA_ASSIST),
            p.order,
        )
    }

    @Test
    fun `an unknown stt value still yields a usable chain`() {
        val p = priorityFor("something-that-does-not-exist")
        assertEquals(listOf(ProviderType.HA_ASSIST, ProviderType.DEEPGRAM), p.order)
        assertFalse(p.usingCloudStt)
    }

    @Test
    fun `every lead keeps at least one fallback`() {
        val leads = listOf(
            VoicePreferences.STT_DASHIE_CLOUD, VoicePreferences.STT_VA_DEFAULT,
            VoicePreferences.STT_HA_ENGINE, VoicePreferences.STT_LOCAL_URL,
            VoicePreferences.STT_ANDROID_VOICE,
        )
        leads.forEach { stt ->
            val order = priorityFor(stt).order
            assertTrue("$stt has no fallback — a single failure would leave the device deaf",
                order.size >= 2)
            assertEquals("$stt has a duplicate in its chain", order.size, order.toSet().size)
        }
    }

    // ── HA Voice Assist mode is NO-CLOUD (2026-07-29) ─────────────────────────
    // The ha_assist preset hides every cloud option in the UI, so no chain may fall back to
    // Deepgram and bill credits for a config the user was never offered. HA_ENGINE
    // (engine-direct Whisper) sits ahead of HA_ASSIST because it bypasses the Assist pipeline
    // and therefore survives a pipeline with no stt_engine.

    private fun vaPriorityFor(stt: String) = SttProviderFactory.resolvePriority(
        customizePipeline = true,
        sttProvider = stt,
        voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT,
    )

    @Test
    fun `ha voice assist never falls back to the cloud`() {
        val leads = listOf(
            VoicePreferences.STT_VA_DEFAULT, VoicePreferences.STT_HA_ENGINE,
            VoicePreferences.STT_LOCAL_URL, VoicePreferences.STT_ANDROID_VOICE,
            VoicePreferences.STT_SHERPA_MOONSHINE_TINY, VoicePreferences.STT_SHERPA_MOONSHINE_BASE,
            VoicePreferences.STT_DASHIE_CLOUD, "something-that-does-not-exist",
        )
        leads.forEach { stt ->
            val p = vaPriorityFor(stt)
            assertFalse("$stt reaches DEEPGRAM in HA Voice Assist mode — that bills credits",
                p.order.contains(ProviderType.DEEPGRAM))
            assertFalse("$stt must not flag cloud STT in HA Voice Assist mode", p.usingCloudStt)
            assertTrue("$stt has no fallback — a single failure would leave the device deaf",
                p.order.size >= 2)
            assertEquals("$stt has a duplicate in its chain", p.order.size, p.order.toSet().size)
        }
    }

    @Test
    fun `on-device stt leads and falls back through engine-direct whisper`() {
        // The Fire-tablet config that surfaced the bug: local Moonshine + HA brain.
        val p = vaPriorityFor(VoicePreferences.STT_SHERPA_MOONSHINE_TINY)
        assertEquals(
            listOf(ProviderType.SHERPA_MOONSHINE_TINY, ProviderType.HA_ENGINE, ProviderType.HA_ASSIST),
            p.order,
        )
        assertFalse(p.coercedFromCloud)
    }

    @Test
    fun `va default keeps engine-direct as the backup that survives an stt-less pipeline`() {
        val p = vaPriorityFor(VoicePreferences.STT_VA_DEFAULT)
        assertEquals(listOf(ProviderType.HA_ASSIST, ProviderType.HA_ENGINE), p.order)
    }

    @Test
    fun `a stale cloud selection is coerced to local and reports it for the DROP warn`() {
        val p = vaPriorityFor(VoicePreferences.STT_DASHIE_CLOUD)
        assertEquals(listOf(ProviderType.HA_ASSIST, ProviderType.HA_ENGINE), p.order)
        assertTrue("the caller needs this to log the DROP: warn", p.coercedFromCloud)
    }

    @Test
    fun `cloud presets are untouched by the no-cloud rule`() {
        // Only ha_assist seeds VOICE_ASSISTANT; cloud/hybrid/local seed DASHIE_CLOUD, so their
        // Deepgram fallbacks must survive.
        val p = SttProviderFactory.resolvePriority(
            customizePipeline = true,
            sttProvider = VoicePreferences.STT_SHERPA_MOONSHINE_TINY,
            voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD,
        )
        assertTrue("hybrid/cloud must keep the cloud fallback",
            p.order.contains(ProviderType.DEEPGRAM))
    }

    // ── Wake-word tail skip (shared with the cascade) ─────────────────────────

    @Test
    fun `whole-clip decoders get the wider tail skip`() {
        // THE CRITERION IS THE DECODER, NOT THE TRANSPORT AND NOT WHERE THE ENGINE RUNS.
        // A decoder handed the complete utterance in one shot cannot revise, so a retained wake
        // tail is committed to the transcript; a streaming socket emits partials and corrects it
        // away. Anything extending BufferedPostSttProvider belongs in the first list.
        //
        // 🔴 This test previously asserted the sherpa values into the SECOND list on the grounds
        // that each "endpoints itself" — true and irrelevant. Endpointing decides where speech
        // ENDS; it does nothing about audio at the START. sherpa is a BufferedPostSttProvider, so
        // the 100ms skip fed a Whisper-family decoder the wake tail, which it rendered as fluent
        // English ("So, here's the key." — T s42 cont.9, Fire, 2026-08-20). The wrong criterion
        // in a passing test is what made that look intentional, so it is spelled out here.
        listOf(
            VoicePreferences.STT_HA_ENGINE, VoicePreferences.STT_LOCAL_URL,
            VoicePreferences.STT_VA_DEFAULT,
            VoicePreferences.STT_SHERPA_MOONSHINE_BASE, VoicePreferences.STT_SHERPA_MOONSHINE_TINY,
        ).forEach {
            assertEquals("$it decodes the whole clip at once", 4480L,
                SttProviderFactory.wakeTailSkipSamples(it))
        }
        // Deepgram streams over a socket and revises its partials. android_voice owns its own mic
        // (AndroidSpeechRecognizerProvider + the coordinator's handoff), so no shared-buffer
        // position is applied to it at all — its value here is inert either way.
        listOf(
            VoicePreferences.STT_DASHIE_CLOUD, VoicePreferences.STT_ANDROID_VOICE,
        ).forEach {
            assertEquals("$it is not a whole-clip decoder", 1600L,
                SttProviderFactory.wakeTailSkipSamples(it))
        }
    }

    // ── Tail skip keyed on the PROVIDER THAT RUNS (2026-08-24) ────────────────
    // The overload above answers "what would this PREFERENCE need?". These answer "what does the
    // engine about to run need?", and the difference is a shipped defect: in HA Voice Assist mode
    // ANDROID_NATIVE was never registered, so a device set to `android_voice` ran HA_ENGINE — a
    // whole-clip Whisper — on the 1600 streaming skip, feeding it the retained wake tail on EVERY
    // turn (S s20 cont.8). The skip must follow the substitution.

    @Test
    fun `tail skip follows the resolved provider, not the preference`() {
        // The exact shipped case: the user picked android_voice, HA_ENGINE actually runs.
        assertEquals("a substituted whole-clip decoder gets the wider skip", 4480L,
            SttProviderFactory.wakeTailSkipSamples(ProviderType.HA_ENGINE))
        // ...while the preference alone would have said otherwise. This pair IS the defect.
        assertEquals("the preference still reads as streaming — why the two overloads differ",
            1600L, SttProviderFactory.wakeTailSkipSamples(VoicePreferences.STT_ANDROID_VOICE))
    }

    @Test
    fun `every provider type resolves to a setting value`() {
        // The completeness gate VALUE_BY_TYPE's KDoc promises. A ProviderType with no value falls
        // to the streaming skip silently — which is precisely the failure above, re-introduced by
        // adding a provider rather than by a bad mapping.
        ProviderType.values().forEach {
            assertTrue("$it has no setting value — wakeTailSkipSamples would silently " +
                "give it the streaming skip", SttProviderFactory.settingValueOf(it) != null)
        }
    }

    @Test
    fun `resolved-provider skips agree with their setting values`() {
        // The two overloads are one decision reached two ways; pin that they cannot disagree.
        ProviderType.values().forEach { type ->
            val viaValue = SttProviderFactory.wakeTailSkipSamples(
                SttProviderFactory.settingValueOf(type)!!)
            assertEquals("$type disagrees with its own setting value",
                viaValue, SttProviderFactory.wakeTailSkipSamples(type))
        }
    }

    @Test
    fun `an unresolvable provider falls back to the streaming skip`() {
        assertEquals("no provider available — the turn fails on that, not on a skip",
            1600L, SttProviderFactory.wakeTailSkipSamples(null as ProviderType?))
    }

    // ── Default derivation when the user has NOT customized the pipeline ──────

    @Test
    fun `without customization the voice method picks the stt`() {
        val cloud = SttProviderFactory.resolveEffectiveStt(
            customizePipeline = false,
            sttProvider = VoicePreferences.STT_LOCAL_URL,
            voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD,
        )
        assertEquals("the explicit sttProvider must be ignored when customize is off",
            VoicePreferences.STT_DASHIE_CLOUD, cloud)

        val va = SttProviderFactory.resolveEffectiveStt(
            customizePipeline = false,
            sttProvider = VoicePreferences.STT_DASHIE_CLOUD,
            voiceControlMethod = "anything-else",
        )
        assertEquals(VoicePreferences.STT_VA_DEFAULT, va)
    }

    @Test
    fun `with customization the explicit choice wins`() {
        val stt = SttProviderFactory.resolveEffectiveStt(
            customizePipeline = true,
            sttProvider = VoicePreferences.STT_HA_ENGINE,
            voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD,
        )
        assertEquals(VoicePreferences.STT_HA_ENGINE, stt)
    }
}

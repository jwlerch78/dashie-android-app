package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.stt.SherpaEngineLoader
import com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * moonshine-tiny's retirement (John, 2026-08-20 "Yes - B").
 *
 * The risk this pins is not "does the picker hide it" — it is the MIGRATION: a device that has
 * tiny selected and downloaded, and does NOT have base, must not be quietly demoted to HA/cloud.
 * That dead-selection shape is the anti-pattern (T s41 cont.7), and it is invisible on any device
 * that happens to have both models.
 */
class SttModelRetirementTest {

    @Test
    fun `a stored tiny selection migrates to base`() {
        assertEquals(
            VoicePreferences.STT_SHERPA_MOONSHINE_BASE,
            SttModelRegistry.migrateRetiredProviderValue(VoicePreferences.STT_SHERPA_MOONSHINE_TINY),
        )
    }

    @Test
    fun `every other selection is left alone`() {
        for (v in listOf(
            VoicePreferences.STT_SHERPA_MOONSHINE_BASE,
            VoicePreferences.STT_ANDROID_VOICE,
            VoicePreferences.STT_VA_DEFAULT,
            VoicePreferences.STT_HA_ENGINE,
            "some_named_local_engine",   // registry engines are not our vocabulary
            "",
        )) {
            assertNull("must not migrate '$v'", SttModelRegistry.migrateRetiredProviderValue(v))
        }
    }

    @Test
    fun `tiny is retired but still RECOGNISED - retired is not unknown`() {
        assertTrue(SherpaEngineLoader.MODEL_MOONSHINE_TINY in SttModelRegistry.RETIRED_FAMILY_IDS)
        // Still resolvable: an installed copy must keep working, and the id must still map.
        assertTrue(SttModelRegistry.byId(SherpaEngineLoader.MODEL_MOONSHINE_TINY) != null)
        assertTrue(SttModelRegistry.byProviderValue(VoicePreferences.STT_SHERPA_MOONSHINE_TINY) != null)
        // …but never offered.
        assertTrue(SttModelRegistry.offeredFamilies().none {
            it.id == SherpaEngineLoader.MODEL_MOONSHINE_TINY
        })
    }

    /** base's chain in the given voice-control mode. */
    private fun baseChain(voiceControlMethod: String) = SttProviderFactory.resolvePriority(
        customizePipeline = true,
        sttProvider = VoicePreferences.STT_SHERPA_MOONSHINE_BASE,
        voiceControlMethod = voiceControlMethod,
    ).order

    @Test
    fun `base's fallback chain keeps tiny as the retirement bridge`() {
        // 🔴 THE ANTI-STRANDING ASSERTION. Without tiny in this chain, migrating a device that has
        // only the old model hands its working on-device STT to HA/cloud — a silent downgrade the
        // user never asked for. Order matters too: base must be preferred once it exists.
        val chain = baseChain(VoicePreferences.VOICE_METHOD_DASHIE_CLOUD)
        assertTrue("base must lead", chain.first() == ProviderType.SHERPA_MOONSHINE_BASE)
        assertTrue("retired tiny must remain reachable as a fallback rung",
            chain.contains(ProviderType.SHERPA_MOONSHINE_TINY))
        assertTrue("local rungs must come before any cloud rung",
            chain.indexOf(ProviderType.SHERPA_MOONSHINE_TINY) < chain.indexOf(ProviderType.DEEPGRAM))
    }

    @Test
    fun `HA Voice Assist mode ALSO keeps the retirement bridge`() {
        // 🔴 THE ASSERTION THAT WAS MISSING, and the reason this defect shipped: the test above
        // pinned the cascade chain and PASSED while haVoiceAssistPriority — a second, separate
        // composition — produced [BASE, HA_ENGINE, HA_ASSIST] with no tiny. On T's fixture (tiny
        // installed, base absent, HA-Voice-Assist mode — likely the MAJORITY config on the
        // "Dashie for HA" lane) the migration fired correctly and the device then logged
        // `DROP: no local STT rung is available` with a working model sitting unused.
        val chain = baseChain(VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT)
        assertTrue("base must lead", chain.first() == ProviderType.SHERPA_MOONSHINE_BASE)
        assertTrue("retired tiny must remain reachable in HA Voice Assist mode too",
            chain.contains(ProviderType.SHERPA_MOONSHINE_TINY))
        // HaLocalSttStage drops HA_ASSIST and runs what is left; tiny must survive that filter,
        // because that filtered list IS what "do we have local STT?" is decided on.
        assertTrue("the bridge must survive HaLocalSttStage's HA_ASSIST filter",
            chain.filter { it != ProviderType.HA_ASSIST }.contains(ProviderType.SHERPA_MOONSHINE_TINY))
        assertFalse("HA Voice Assist is a no-cloud mode — the bridge must not open a cloud path",
            chain.contains(ProviderType.DEEPGRAM))
    }

    @Test
    fun `EVERY mode composing base's chain gets the bridge`() {
        // The durable form of the two tests above: the bridge is DERIVED from one mapping, so a
        // mode added tomorrow inherits it. Enumerating the modes here is what makes "written
        // twice" fail the build rather than ship — patching one copy is how the second was missed.
        for (mode in listOf(
            VoicePreferences.VOICE_METHOD_DASHIE_CLOUD,
            VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT,
            "some-future-or-unknown-method",
        )) {
            val chain = baseChain(mode)
            assertEquals("$mode: base must lead", ProviderType.SHERPA_MOONSHINE_BASE, chain.first())
            assertEquals("$mode: the bridge must sit directly behind its replacement",
                ProviderType.SHERPA_MOONSHINE_TINY, chain[1])
            assertEquals("$mode has a duplicate rung", chain.size, chain.toSet().size)
        }
    }

    @Test
    fun `a NON-replacement lead gets no bridge`() {
        // The bridge is keyed to the retired model's replacement, not sprayed across every chain:
        // HA Assist must not acquire a phantom on-device rung it cannot run.
        for (stt in listOf(
            VoicePreferences.STT_VA_DEFAULT,
            VoicePreferences.STT_HA_ENGINE,
            VoicePreferences.STT_ANDROID_VOICE,
        )) {
            for (mode in listOf(
                VoicePreferences.VOICE_METHOD_DASHIE_CLOUD,
                VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT,
            )) {
                val chain = SttProviderFactory.resolvePriority(
                    customizePipeline = true, sttProvider = stt, voiceControlMethod = mode).order
                assertFalse("$stt/$mode must not gain a retired on-device rung",
                    chain.contains(ProviderType.SHERPA_MOONSHINE_TINY))
            }
        }
    }
}

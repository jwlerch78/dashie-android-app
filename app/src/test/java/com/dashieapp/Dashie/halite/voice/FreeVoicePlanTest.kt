package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The $0 fallback matrix (WS-D.1). Pure resolver, so no Android needed.
 *
 * The invariant this file exists to defend: **no branch may ever include a billed engine.**
 * If DEEPGRAM or TTS_DASHIE_CLOUD leaks into a plan, an out-of-credits user gets charged (or
 * silently rejected) by the very path meant to rescue them — see [noPlanEverUsesABilledEngine].
 */
class FreeVoicePlanTest {

    private fun plan(
        ha: Boolean = false,
        haStt: String = "",
        localStt: String = "",
        localTts: String = "",
        haTts: String = "",
        androidStt: Boolean = false,
    ) = FreeVoicePlan.resolve(ha, haStt, localStt, localTts, haTts, androidStt)

    // ── the dead end (the honest case) ───────────────────────────────────

    @Test
    fun androidRecognizerIsTheLastRungWhenAvailable() {
        // The plug-and-play case: no HA, no Whisper box, but the built-in recognizer works.
        val p = plan(androidStt = true)
        assertEquals(listOf(ProviderType.ANDROID_NATIVE), p.sttPriority)
        assertTrue(p.anyFreeStt)
        assertFalse(p.isDeadEnd)
    }

    @Test
    fun androidRecognizerRanksBelowHaAndOwnBox() {
        // It hands the mic away for the session (no AEC, no barge-in), so it's the fallback of
        // last resort even though it needs no configuration.
        val p = plan(ha = true, localStt = "http://box:9000", androidStt = true)
        assertEquals(
            listOf(ProviderType.LOCAL_WHISPER, ProviderType.HA_ASSIST, ProviderType.ANDROID_NATIVE),
            p.sttPriority,
        )
    }

    @Test
    fun noHaNoLocalWhisperAndNoRecognizerIsADeadEnd() {
        // Fire OS / Lineage: no Google services, so the recognizer isn't available either.
        val p = plan(androidStt = false)
        assertFalse("no free transcript is possible", p.anyFreeStt)
        assertTrue(p.isDeadEnd)
        assertTrue(p.sttPriority.isEmpty())
        assertNull("a dead end announces nothing — the caller shows the CR2 prompt", p.announcement)
    }

    @Test
    fun deadEndStillPicksAFreeTtsSoTheBlockCanBeSpoken() {
        // Even with nothing to transcribe, the prompt/notice may still speak — it must not
        // pick the billed cloud voice to say "you're out of credits".
        assertEquals(VoicePreferences.TTS_ANDROID_VOICE, plan().ttsProvider)
    }

    // ── HA configured ────────────────────────────────────────────────────

    @Test
    fun haAloneGivesAssistSttAndTheHaConversationAgent() {
        val p = plan(ha = true)
        assertEquals(listOf(ProviderType.HA_ASSIST), p.sttPriority)
        assertTrue(p.anyFreeStt)
        assertTrue("HA's agent is free, so questions still work", p.useBrain)
        assertTrue(p.useHaConversation)
        // HA alone (no direct Piper engine) → the DEVICE's TTS, NOT the HA pipeline's va_default:
        // the pipeline TTS hard-fails when HA has no pipeline TTS engine, so the fallback must
        // speak reliably on its own (2026-07-22).
        assertEquals(VoicePreferences.TTS_ANDROID_VOICE, p.ttsProvider)
        assertEquals(FreeVoicePlan.ANNOUNCE_LOCAL, p.announcement)
    }

    @Test
    fun haEngineDirectWhisperLeadsOverAssistWhenConfigured() {
        val p = plan(ha = true, haStt = "whisper")
        assertEquals(listOf(ProviderType.HA_ENGINE, ProviderType.HA_ASSIST), p.sttPriority)
    }

    @Test
    fun haEngineIdWithoutHaConfiguredIsIgnored() {
        // A stale engine id can outlive the HA connection — it must not produce a lead
        // provider that can't authenticate.
        val p = plan(ha = false, haStt = "whisper")
        assertTrue(p.sttPriority.isEmpty())
        assertTrue(p.isDeadEnd)
    }

    // ── own-box Whisper ──────────────────────────────────────────────────

    @Test
    fun ownBoxWhisperLeadsAndWorksWithoutHa() {
        val p = plan(localStt = "http://box:9000")
        assertEquals(listOf(ProviderType.LOCAL_WHISPER), p.sttPriority)
        assertTrue(p.anyFreeStt)
        // No HA ⇒ no free brain, but the device-control lane still works.
        assertFalse(p.useBrain)
        assertEquals(FreeVoicePlan.ANNOUNCE_DEVICE_ONLY, p.announcement)
    }

    @Test
    fun ownBoxWhisperLeadsAheadOfHa() {
        val p = plan(ha = true, haStt = "whisper", localStt = "http://box:9000")
        assertEquals(
            listOf(ProviderType.LOCAL_WHISPER, ProviderType.HA_ENGINE, ProviderType.HA_ASSIST),
            p.sttPriority,
        )
    }

    // ── TTS ladder ───────────────────────────────────────────────────────

    @Test
    fun ttsPrefersOwnBoxThenDirectHaEngineThenDevice_neverThePipelineTts() {
        assertEquals(VoicePreferences.TTS_LOCAL_URL, plan(localTts = "http://box:8880").ttsProvider)
        assertEquals(VoicePreferences.TTS_HA_ENGINE, plan(ha = true, haTts = "piper").ttsProvider)
        // HA configured but NO direct Piper engine → device TTS, NOT va_default (the HA pipeline's
        // own TTS): va_default fails outright when the pipeline has no TTS engine, so it's never
        // the free fallback (2026-07-22).
        assertEquals(VoicePreferences.TTS_ANDROID_VOICE, plan(ha = true).ttsProvider)
        assertEquals(VoicePreferences.TTS_ANDROID_VOICE, plan().ttsProvider)
    }

    @Test
    fun haTtsEngineWithoutHaConfiguredFallsToDevice() {
        assertEquals(VoicePreferences.TTS_ANDROID_VOICE, plan(ha = false, haTts = "piper").ttsProvider)
    }

    // ── the invariant ────────────────────────────────────────────────────

    @Test
    fun noPlanEverUsesABilledEngine() {
        val flags = listOf(true, false)
        for (ha in flags) for (haStt in listOf("", "whisper")) for (localStt in listOf("", "u"))
            for (localTts in listOf("", "u")) for (haTts in listOf("", "piper")) for (androidStt in flags) {
                val p = plan(ha, haStt, localStt, localTts, haTts, androidStt)
                assertFalse(
                    "DEEPGRAM (billed) leaked into a \$0 plan: ha=$ha haStt=$haStt localStt=$localStt",
                    p.sttPriority.contains(ProviderType.DEEPGRAM),
                )
                assertEquals(
                    "cloud TTS (billed) leaked into a \$0 plan",
                    false, p.ttsProvider == VoicePreferences.TTS_DASHIE_CLOUD,
                )
            }
    }

    @Test
    fun anyFreeSttAlwaysAgreesWithThePriorityList() {
        val flags = listOf(true, false)
        for (ha in flags) for (localStt in listOf("", "u")) for (androidStt in flags) {
            val p = plan(ha = ha, localStt = localStt, androidStt = androidStt)
            assertEquals(p.sttPriority.isNotEmpty(), p.anyFreeStt)
            assertEquals(p.sttPriority.isEmpty(), p.isDeadEnd)
            // A usable plan must always explain itself; a dead end never does.
            if (p.anyFreeStt) assertNotNull(p.announcement) else assertNull(p.announcement)
        }
    }
}

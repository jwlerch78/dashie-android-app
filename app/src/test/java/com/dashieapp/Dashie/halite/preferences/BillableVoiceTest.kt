package com.dashieapp.Dashie.halite.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins [VoicePreferences.isBillableVoice] — the gate for every credit UI (card red /
 * "No credits", low badge, low-credit + $0 pill). Regression guard for the punch #5
 * fix: billability is judged from the CONCRETE engine keys, NOT gated on
 * `customizePipeline`. The old `!customizePipeline → false` early-return read the Fire's
 * stt=dashie_cloud + customize=false config as non-billable, so the $0 flow never fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillableVoiceTest {

    private lateinit var voice: VoicePreferences

    @Before fun setup() {
        voice = VoicePreferences(RuntimeEnvironment.getApplication())
        voice.voiceEnabled = true
    }

    @Test fun `dashie cloud method is billable`() {
        voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
        assertTrue(voice.isBillableVoice)
    }

    @Test fun `cloud STT bills even when not customized - the Fire bug`() {
        voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
        voice.customizePipeline = false
        voice.sttProvider = VoicePreferences.STT_DASHIE_CLOUD
        voice.ttsProvider = VoicePreferences.TTS_VA_DEFAULT
        voice.aiModel = VoicePreferences.AI_MODEL_HOME_ASSISTANT
        assertTrue("cloud STT bills regardless of customizePipeline", voice.isBillableVoice)
    }

    @Test fun `fully HA voice assistant is not billable`() {
        voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
        voice.customizePipeline = false
        voice.sttProvider = VoicePreferences.STT_VA_DEFAULT
        voice.ttsProvider = VoicePreferences.TTS_VA_DEFAULT
        voice.aiModel = VoicePreferences.AI_MODEL_HOME_ASSISTANT
        assertFalse(voice.isBillableVoice)
    }

    @Test fun `cloud AI model with local voice is billable`() {
        voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
        voice.customizePipeline = true
        voice.sttProvider = VoicePreferences.STT_VA_DEFAULT
        voice.ttsProvider = VoicePreferences.TTS_VA_DEFAULT
        voice.aiModel = "gemini-2.5-flash"
        assertTrue(voice.isBillableVoice)
    }

    @Test fun `local own-AI model is not billable`() {
        // "My own AI" (ai.model=local) is a free BYO model — no credit UI (John 2026-07-22:
        // switching to Local was wrongly showing the low-credit message).
        voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_VOICE_ASSISTANT
        voice.customizePipeline = true
        voice.sttProvider = VoicePreferences.STT_VA_DEFAULT
        voice.ttsProvider = VoicePreferences.TTS_ANDROID_VOICE
        voice.aiModel = VoicePreferences.AI_MODEL_LOCAL
        assertFalse(voice.isBillableVoice)
    }

    @Test fun `disabled voice is never billable`() {
        voice.voiceEnabled = false
        voice.voiceControlMethod = VoicePreferences.VOICE_METHOD_DASHIE_CLOUD
        assertFalse(voice.isBillableVoice)
    }
}

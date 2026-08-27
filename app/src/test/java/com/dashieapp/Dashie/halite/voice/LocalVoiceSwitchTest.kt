package com.dashieapp.Dashie.halite.voice

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins what "Use local voice" (LocalVoiceSwitch.persist) resolves to per device capability (punch
 * #5). Notably the deliberate DEAD-END: a Google-services tablet with no HA and no local box does
 * NOT get a persisted Android-only config — Android STT is cascade-only with no clean non-billable,
 * no-brain persisted state, so persist declines and the caller points the user at Settings. The $0
 * TEMP fallback still covers Android voice at runtime (see FreeVoicePlanTest). Also pins the
 * disconnected-bug fix: the ha_assist persist never leaves the fragile va_default pipeline TTS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalVoiceSwitchTest {

    private lateinit var ctx: Context
    private lateinit var prefs: HalitePreferences

    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = HalitePreferences(ctx)
        prefs.connection.haBaseUrl = ""
        prefs.connection.haAccessToken = ""
        prefs.voice.localSttUrl = ""
        prefs.voice.localTtsUrl = ""
    }

    @Test fun `no HA and no local box is an honest dead-end`() {
        // Persist declines (null) — no config is written. The temp $0 fallback still uses Android
        // STT+TTS at runtime; a PERSISTED android-only state is a deliberate future add.
        assertNull(LocalVoiceSwitch.persist(ctx))
    }

    @Test fun `HA configured persists ha_assist`() {
        prefs.connection.haBaseUrl = "http://ha.local:8123"
        assertEquals("ha_assist", LocalVoiceSwitch.persist(ctx))
    }

    @Test fun `a local box persists local`() {
        prefs.voice.localSttUrl = "http://box:9000"
        assertEquals("local", LocalVoiceSwitch.persist(ctx))
    }

    @Test fun `ha_assist persist never uses the fragile va_default pipeline TTS`() {
        prefs.connection.haBaseUrl = "http://ha.local:8123"
        LocalVoiceSwitch.persist(ctx)
        // The disconnected-bug fix: va_default (the HA pipeline's own TTS) hard-fails when HA has
        // no pipeline TTS engine, so the persist swaps it for a reliable direct/Android TTS.
        assertNotEquals(VoicePreferences.TTS_VA_DEFAULT, prefs.voice.ttsProvider)
    }
}

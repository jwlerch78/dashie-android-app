package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vectors pinning [VoiceLabelDecoder] to the console decoder it mirrors
 * (dashie-console `_piperVoiceLabel` / `localVoiceLabel`) — punch #3, contract #44.
 * Display-only, so a drift is cosmetic, but these keep the two in step.
 */
class VoiceLabelDecoderTest {

    private val D = VoiceLabelDecoder

    // ── Piper: <speaker>-<quality> id and the "amy (low)" name form ──
    @Test fun `piper low is fast`() = assertEquals("Amy (fast)", D.piperVoiceLabel(null, "en_US-amy-low"))
    @Test fun `piper medium is balanced`() =
        assertEquals("Lessac (balanced)", D.piperVoiceLabel(null, "en_US-lessac-medium"))
    @Test fun `piper high is high quality`() =
        assertEquals("Ryan (high quality)", D.piperVoiceLabel(null, "en_US-ryan-high"))
    @Test fun `piper paren name form`() =
        assertEquals("Amy (fast)", D.piperVoiceLabel("amy (low)", "en_US-amy-low"))
    @Test fun `piper unrecognized is just capitalized`() =
        assertEquals("Randomname", D.piperVoiceLabel(null, "randomname"))

    // ── Kokoro: <lang><gender>_<name> ──
    @Test fun `kokoro american female`() =
        assertEquals("Heart (American female)", D.localVoiceLabel("af_heart"))
    @Test fun `kokoro american female nicole`() =
        assertEquals("Nicole (American female)", D.localVoiceLabel("af_nicole"))
    @Test fun `kokoro japanese male`() =
        assertEquals("Kumo (Japanese male)", D.localVoiceLabel("jm_kumo"))

    // ── localVoiceLabel dispatches Piper ids to the piper decoder ──
    @Test fun `local dispatches piper id`() =
        assertEquals("Amy (fast)", D.localVoiceLabel("en_US-amy-low"))
    @Test fun `local unrecognized keeps fallback`() =
        assertEquals("weirdvoice", D.localVoiceLabel("weirdvoice", "weirdvoice"))
}

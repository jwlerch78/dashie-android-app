package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context

/**
 * Native Inworld TTS-2 cloud TTS. Thin vendor binding over [CloudTtsClient] — reuses all
 * the streaming/billing/AEC/generation logic. See [TtsVendor.INWORLD] for the endpoint /
 * default voice (Ashley) / model (inworld-tts-2). Selected via the internal
 * `voice.cloudTtsVendor` toggle in [com.dashieapp.Dashie.halite.voice.NativeResponseTts].
 */
class InworldTtsClient(context: Context) : CloudTtsClient(context, TtsVendor.INWORLD)

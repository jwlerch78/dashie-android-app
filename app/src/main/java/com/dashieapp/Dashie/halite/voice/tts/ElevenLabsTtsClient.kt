package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context

/**
 * Native ElevenLabs cloud TTS. Thin vendor binding over [CloudTtsClient] — all playback,
 * streaming, billing, AEC and generation logic lives in the base. See [TtsVendor.ELEVENLABS]
 * for the endpoint / default voice (Bella) / model (Flash v2.5).
 */
class ElevenLabsTtsClient(context: Context) : CloudTtsClient(context, TtsVendor.ELEVENLABS)

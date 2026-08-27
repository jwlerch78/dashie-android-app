package com.dashieapp.Dashie.halite.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Stateless PCM sample generator for alert tones (chimes and alarms).
 *
 * Generates 16-bit mono samples at 44.1 kHz with configurable volume
 * and fade envelopes to avoid clicks. Used by [AlertSoundService].
 */
object AlertToneGenerator {
    const val SAMPLE_RATE = 44100
    private const val FADE_MS = 5

    // Musical note frequencies (Hz)
    private const val F4 = 349.23
    private const val A4 = 440.00
    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val A5 = 880.00

    data class Tone(val frequency: Double, val durationMs: Int, val volume: Float)

    // ── Chime Definitions ─────────────────────────────────────────────

    /** Ascending C5 → E5 → G5 major arpeggio */
    fun brightChime(volume: Float): List<Tone> = listOf(
        Tone(C5, 90, volume), Tone(0.0, 15, 0f),
        Tone(E5, 90, volume), Tone(0.0, 15, 0f),
        Tone(G5, 120, volume)
    )

    /** Soft F4 → A4 two-note */
    fun gentleChime(volume: Float): List<Tone> = listOf(
        Tone(F4, 120, volume * 0.75f), Tone(0.0, 20, 0f),
        Tone(A4, 140, volume * 0.75f)
    )

    /** Descending G5 → E5 → C5 */
    fun alertChime(volume: Float): List<Tone> = listOf(
        Tone(G5, 90, volume), Tone(0.0, 15, 0f),
        Tone(E5, 90, volume), Tone(0.0, 15, 0f),
        Tone(C5, 120, volume)
    )

    /** Classic ding-dong: E5 → C5 with longer sustain */
    fun doorbellChime(volume: Float): List<Tone> = listOf(
        Tone(E5, 180, volume), Tone(0.0, 30, 0f),
        Tone(C5, 220, volume)
    )

    /** Single clean A4 tone */
    fun simpleChime(volume: Float): List<Tone> = listOf(
        Tone(A4, 200, volume)
    )

    // ── Sample Generation ─────────────────────────────────────────────

    /**
     * Generate PCM samples for a sequence of tones.
     * @return Pair of (samples, total duration in ms)
     */
    fun generateChimeSamples(tones: List<Tone>): Pair<ShortArray, Int> {
        val totalSamples = tones.sumOf { (it.durationMs * SAMPLE_RATE) / 1000 }
        val samples = ShortArray(totalSamples)
        val fadeSamples = (FADE_MS * SAMPLE_RATE) / 1000

        var offset = 0
        for (tone in tones) {
            val count = (tone.durationMs * SAMPLE_RATE) / 1000
            if (tone.frequency <= 0.0) {
                offset += count
                continue
            }
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = when {
                    i < fadeSamples -> i.toFloat() / fadeSamples
                    i > count - fadeSamples -> (count - i).toFloat() / fadeSamples
                    else -> 1.0f
                }
                samples[offset + i] = (sin(2.0 * PI * tone.frequency * t) *
                    Short.MAX_VALUE * tone.volume * envelope).toInt().toShort()
            }
            offset += count
        }
        val durationMs = tones.sumOf { it.durationMs }
        return Pair(samples, durationMs)
    }

    /**
     * Generate one alarm cycle: [beep][gap][beep][gap][beep][silence].
     * Pattern: 3 × 150ms beeps at 880Hz (A5) with 100ms gaps, then 1.2s silence.
     * Designed for indefinite looping via AudioTrack.setLoopPoints().
     */
    fun generateAlarmCycle(volume: Float): ShortArray {
        val beepMs = 150
        val gapMs = 100
        val beepsPerCycle = 3
        val silenceMs = 1200

        val beepSamples = (beepMs * SAMPLE_RATE) / 1000
        val gapSamples = (gapMs * SAMPLE_RATE) / 1000
        val silenceSamples = (silenceMs * SAMPLE_RATE) / 1000
        val fadeSamples = (FADE_MS * SAMPLE_RATE) / 1000

        val cycleLength = beepsPerCycle * beepSamples +
            (beepsPerCycle - 1) * gapSamples + silenceSamples
        val samples = ShortArray(cycleLength)

        var offset = 0
        for (b in 0 until beepsPerCycle) {
            for (i in 0 until beepSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = when {
                    i < fadeSamples -> i.toFloat() / fadeSamples
                    i > beepSamples - fadeSamples -> (beepSamples - i).toFloat() / fadeSamples
                    else -> 1.0f
                }
                samples[offset + i] = (sin(2.0 * PI * A5 * t) *
                    Short.MAX_VALUE * volume * envelope).toInt().toShort()
            }
            offset += beepSamples
            if (b < beepsPerCycle - 1) offset += gapSamples
        }
        return samples
    }
}

package com.dashieapp.Dashie.halite.voice.stt

import android.os.Debug
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives one benchmark utterance through a REAL [SttProvider] and measures
 * **time-to-final-transcript from end-of-speech** — the metric that actually
 * describes how long a user waits after they stop talking.
 *
 * Why this rather than a decode-time microbenchmark: RTF hides the whole
 * problem. A streaming provider uploads while the user is still speaking, so
 * its wall-clock wait can beat a local model with a far better RTF; a buffered
 * provider can't start until end-of-audio. The only comparable number across
 * both shapes is measured from the same anchor:
 *
 *     t(onFinalResult) − t(endAudioStream)
 *
 * Audio is paced at 1× real time in [CHUNK_MS] slices, exactly as
 * SharedAudioBuffer feeds the providers live. Dumping the whole buffer at once
 * would let a streaming provider "hear" the utterance instantly and report a
 * latency no user will ever experience.
 *
 * ⚠️ NOT measured here: the VAD endpointer's own delay (Silero `minSilenceMs`,
 * 500 ms by default). Clips are trimmed to end-of-speech, so this measures the
 * ENGINE's contribution. Perceived latency is endpointer + this. Reported
 * separately on purpose — the endpointer is a shared constant across engines,
 * so folding it in would compress the differences this bench exists to show.
 *
 * ⚠️ ANDROID_NATIVE (SpeechRecognizer) cannot be benched here: it owns its own
 * microphone and ignores [SttProvider.streamAudio], so there is no way to feed
 * it a corpus clip. It is rejected with a loud DROP rather than silently
 * returning a meaningless zero.
 */
object SttBenchRunner {
    private const val TAG = "SttBench"
    private const val CHUNK_MS = 20L
    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000  // 16-bit mono
    private const val DEFAULT_TIMEOUT_MS = 60_000L
    // Mic mode: how long to keep the session open after the clip should have
    // finished playing, so a trailing word isn't clipped by us rather than by
    // the engine's own endpointer.
    private const val MIC_TAIL_MS = 1_200L
    /** Sample PSS every N chunks (~500 ms) — see the pacing loop for why. */
    private const val PSS_SAMPLE_CHUNKS = 25

    data class Result(
        val provider: String,
        val transcript: String,
        val ttftMs: Long,          // end-of-audio → final transcript
        val audioMs: Long,
        val pssBeforeKb: Int,
        val pssAfterKb: Int,
        val pssPeakKb: Int,
        val error: String? = null,
        val ttftSource: String = "listener",  // or "provider" (lastTranscribeMs)
        /**
         * Wall time the 1x pacing loop ACTUALLY took. If this exceeds [audioMs] by
         * more than a few percent the device could not hold real-time timing (CPU
         * contention — RTSP, other apps, low RAM), the stream was stretched, and
         * every latency derived from it is meaningless. Observed 2026-08-05: a
         * 2.5 s clip streamed over ~10 s while RTSP ran, which read as Deepgram
         * "failing to endpoint" when it was the harness losing the clock.
         */
        val streamedMs: Long = 0,
        /** Which engine ACTUALLY answered — differs from [provider] only on a fallback. */
        val actualProvider: String? = null,
    )

    /** Read a 16-bit PCM WAV, returning the data chunk only. */
    fun readWavPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "${file.name}: too short to be a WAV" }
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "${file.name}: not a RIFF/WAVE file"
        }
        // Walk the chunk list rather than assuming a 44-byte header — writers
        // that emit LIST/fact chunks would otherwise inject metadata as audio.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val end = minOf(pos + 8 + size, bytes.size)
                return bytes.copyOfRange(pos + 8, end)
            }
            pos += 8 + size + (size and 1)  // chunks are word-aligned
        }
        throw IllegalArgumentException("${file.name}: no data chunk")
    }

    private fun pssKb(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss
    }

    /**
     * Run one clip through [type]. Blocking — call off the main thread.
     */
    fun run(
        manager: SttProviderManager,
        type: SttProviderManager.ProviderType,
        pcm: ByteArray,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        micMode: Boolean = false,
        leadMs: Long = 0L,
    ): Result {
        val name = type.name
        val audioMs = (pcm.size / BYTES_PER_MS).toLong()

        // ANDROID_NATIVE can ONLY be benched in mic mode: it captures its own
        // audio and ignores streamAudio. Going through the REGISTERED provider
        // matters — SttProviderFactory only registers it with a mic handoff, and
        // that handoff is what releases the wake-word shared capture. A private
        // SpeechRecognizer just loses the mic race (see SttBenchNativeRecognizer).
        if (type == SttProviderManager.ProviderType.ANDROID_NATIVE && !micMode) {
            return Result(name, "", -1, audioMs, 0, 0, 0,
                "DROP: ANDROID_NATIVE owns its own mic — bench it with micMode=true " +
                    "(loudspeaker rig) instead of feeding it corpus audio")
        }
        val provider = manager.getProvider(type)
            ?: return Result(name, "", -1, audioMs, 0, 0, 0, "DROP: provider not registered")
        if (!provider.isAvailable()) {
            return Result(name, "", -1, audioMs, 0, 0, 0, "DROP: provider unavailable")
        }

        val pssBefore = pssKb()
        val peak = AtomicLong(pssBefore.toLong())
        val done = CountDownLatch(1)
        val transcript = AtomicReference("")
        val failure = AtomicReference<String?>(null)
        val finalAt = AtomicLong(0)

        val listener = object : SttListener {
            override fun onSessionStarted() {}
            override fun onInterimResult(text: String) {}
            override fun onFinalResult(text: String) {
                // Stamp FIRST — anything else here inflates the measurement.
                finalAt.set(System.nanoTime())
                transcript.set(text)
                done.countDown()
            }
            override fun onNoSpeechDetected() {
                finalAt.set(System.nanoTime())
                failure.compareAndSet(null, "no speech detected")
                done.countDown()
            }
            override fun onError(error: String, isRecoverable: Boolean) {
                failure.compareAndSet(null, error)
                done.countDown()
            }
            override fun onSessionEnded() {}
        }

        if (!manager.startSession(listener, preferredType = type)) {
            return Result(name, "", -1, audioMs, pssBefore, pssKb(), pssBefore,
                "DROP: startSession refused")
        }

        val streamStartNs = System.nanoTime()
        if (micMode) {
            // LOUDSPEAKER RIG: stream nothing. The session is live and the provider
            // is listening to the real microphone while the harness plays the clip
            // aloud beside the device.
            //
            // `leadMs` is the harness's head start — it waits the same amount after
            // firing the request before it starts playback, so the session is
            // certain to be listening first. It is passed in rather than defined on
            // both sides: one value, one owner, nothing to drift.
            Thread.sleep(leadMs + audioMs + MIC_TAIL_MS)
            peak.set(maxOf(peak.get(), pssKb().toLong()))
        } else {
            // Pace at 1× real time so streaming providers get the same head start
            // they get from a live speaker, and buffered ones the same standing wait.
            val chunk = (CHUNK_MS * BYTES_PER_MS).toInt()
            val startNs = System.nanoTime()
            var offset = 0
            var sent = 0L
            var chunksSincePss = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunk, pcm.size)
                manager.streamAudio(pcm.copyOfRange(offset, end))
                offset = end
                sent += CHUNK_MS
                // ⚠️ NEVER sample PSS every chunk. Debug.getMemoryInfo() walks
                // /proc/self/smaps and costs tens of ms — inside a loop with a
                // 20 ms budget it stretched the stream ~3.3x (a 3 s clip streamed
                // over 10 s), which then read as the ENGINE failing to endpoint.
                // The measurement was destroying the thing it measured. Sampling
                // every PSS_SAMPLE_CHUNKS keeps the peak useful and the clock honest.
                if (++chunksSincePss >= PSS_SAMPLE_CHUNKS) {
                    chunksSincePss = 0
                    peak.set(maxOf(peak.get(), pssKb().toLong()))
                }
                val behind = sent - (System.nanoTime() - startNs) / 1_000_000
                if (behind > 0) Thread.sleep(behind)
            }
        }

        val eosNs = System.nanoTime()
        val streamedMs = (eosNs - streamStartNs) / 1_000_000
        // In mic mode the provider may already have endpointed on its own VAD —
        // harmless, and the ttft anchor falls back to lastTranscribeMs below.
        manager.endAudioStream()

        val completed = done.await(timeoutMs, TimeUnit.MILLISECONDS)

        // ⚠️ SttProviderManager FALLS BACK on primary failure ("Primary provider
        // failed, falling back to: ha_assist"). Silently benching a different
        // engine under the requested engine's name is the worst thing this
        // harness could do — it produced 40 rows labelled DEEPGRAM that were
        // actually HA Assist (2026-08-05, Deepgram's WS 502'd). Fail loud instead.
        val actual = manager.activeProviderId
        if (actual != null && actual != provider.providerId) {
            Log.w(TAG, "DROP: [$name] manager fell back to '$actual' — not benching $name")
            return Result(name, "", -1, audioMs, pssBefore, pssKb(), peak.get().toInt(),
                "DROP: fell back to '$actual' (requested ${provider.providerId}) — " +
                    "result would misattribute another engine",
                actualProvider = actual, streamedMs = streamedMs)
        }

        val pssAfter = pssKb()
        peak.set(maxOf(peak.get(), pssAfter.toLong()))

        if (!completed) {
            manager.cancelSession()
            Log.w(TAG, "DROP: [$name] no final transcript within ${timeoutMs}ms")
            return Result(name, "", -1, audioMs, pssBefore, pssAfter, peak.get().toInt(),
                "timeout after ${timeoutMs}ms", streamedMs = streamedMs)
        }

        // Buffered providers that endpoint on their OWN VAD fire onFinalResult
        // before our endAudioStream anchor lands, racing the measurement to ~0.
        // They publish the true figure on lastTranscribeMs — prefer it, and say
        // which source produced the number rather than quietly mixing the two.
        val providerMs = provider.lastTranscribeMs
        val listenerMs = (finalAt.get() - eosNs) / 1_000_000
        val (ttft, source) = when {
            providerMs != null -> providerMs to "provider"
            // NEGATIVE IS MEANINGFUL — do not clamp. When the harness pads the
            // clip with trailing silence (mimicking real capture, where the mic
            // runs on after the talker stops), a streaming engine can endpoint
            // DURING the padding and finalize before end-of-audio. Clamping that
            // to 0 reported "instant" for what is really "400 ms after speech
            // ended, which happens to be 100 ms before we stopped streaming".
            // The harness adds the pad back to recover time-from-end-of-SPEECH.
            else -> listenerMs to "listener"
        }

        val drift = streamedMs - audioMs
        if (drift > audioMs / 10) {
            Log.w(TAG, "DROP-WORTHY: [$name] pacing drifted ${drift}ms over ${audioMs}ms " +
                "of audio — device could not hold 1x; latency from this row is not trustworthy")
        }
        Log.i(TAG, "[$name] audio=${audioMs}ms streamed=${streamedMs}ms ttft=${ttft}ms ($source) " +
            "pss ${pssBefore}→${pssAfter}KB peak=${peak.get()}KB")
        return Result(name, transcript.get(), ttft, audioMs, pssBefore, pssAfter,
            peak.get().toInt(), failure.get(), source, streamedMs = streamedMs)
    }
}

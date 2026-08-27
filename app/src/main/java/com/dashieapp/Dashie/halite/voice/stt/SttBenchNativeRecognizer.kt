package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Benches Android's [SpeechRecognizer] by FEEDING IT A FILE.
 *
 * ⚠️ DEVICE-VERIFIED 2026-07-29 ON SAMSUNG SM-X200 (Android 14): NEITHER MODE
 * HERE PRODUCES A TRANSCRIPT. Kept for the record, and because the result is
 * device-specific — re-probe before assuming it holds elsewhere.
 *
 *  - `createOnDeviceSpeechRecognizer` never engages at all: no onReadyForSpeech,
 *    no onError, nothing, in either fed-file or mic mode. Just a timeout.
 *  - `createSpeechRecognizer` (Google) DOES engage — onReadyForSpeech and
 *    onBeginningOfSpeech both fire — but returns an EMPTY transcript from a fed
 *    file, i.e. it does not honor EXTRA_AUDIO_SOURCE, and ERROR_NO_MATCH(7) in
 *    mic mode.
 *  - The mic-mode failure has a KNOWN cause: Dashie's own wake-word shared
 *    capture holds the microphone (`dumpsys audio` → `pack:com.dashieapp.Dashie
 *    .local ... src client=MIC`), so a second recognizer opening the mic gets
 *    silence. SttProviderFactory already says this — ANDROID_NATIVE is
 *    registered ONLY when a mic handoff is supplied, precisely because it
 *    "captures its OWN audio and would otherwise fight the shared capture".
 *
 * THE CORRECT ROUTE for a loudspeaker rig is therefore NOT this class: drive the
 * REGISTERED ANDROID_NATIVE provider through SttProviderManager, which owns that
 * handoff, and simply don't pace PCM into it — let it hear the clip played
 * aloud. This class opens its own recognizer and so can never get the mic.
 *
 * Every other engine implements [SttProvider], so [SttBenchRunner] can pace PCM
 * into it. SpeechRecognizer can't: it owns its own microphone and ignores
 * `streamAudio`, which is why it was excluded from the first benchmark and why
 * the fallback plan was a loudspeaker rig.
 *
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 31) is the escape hatch — it hands
 * the recognizer a [ParcelFileDescriptor] to read audio from instead of the mic,
 * with the format declared via the three companion extras. Support is
 * IMPLEMENTATION-DEPENDENT: the extras exist on every API 31+ device, but an
 * OEM/Google recognizer is free to ignore them and open the mic anyway. That
 * failure is silent and would look like a terrible WER rather than a broken
 * harness, so [looksLikeMicFallback] flags the signature — a run in a quiet room
 * that returns nothing but ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT means the file
 * was never read.
 *
 * The PFD carries RAW PCM, not a WAV: rate/encoding/channel count travel in the
 * extras, so a 44-byte header would be decoded as audio.
 *
 * If this path doesn't work on the target device, the loudspeaker rig is the
 * only remaining option — and it measures a different thing (the full acoustic
 * chain, including the device's own AGC and noise suppression), so the two are
 * not interchangeable.
 */
object SttBenchNativeRecognizer {
    private const val TAG = "SttBench"
    private const val SAMPLE_RATE = 16000

    data class Result(
        val transcript: String,
        val ttftMs: Long,
        val error: String? = null,
        val recognizer: String = "default",
        val lastErrorCode: Int = -1,
    )

    /** ERROR_NO_MATCH(7) / ERROR_SPEECH_TIMEOUT(6) on a fed file = extras ignored. */
    fun looksLikeMicFallback(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    /**
     * @param onDevice prefer [SpeechRecognizer.createOnDeviceSpeechRecognizer] (API 33+).
     *   The on-device recognizer is the one worth comparing — the network one is a
     *   different product with a different latency story.
     */
    /**
     * @param fedAudio false = PURE MIC MODE: no EXTRA_AUDIO_SOURCE at all, the
     *   recognizer opens the microphone as it does in production. This is the
     *   loudspeaker-rig path — the harness plays the clip aloud near the device
     *   and the recognizer hears it, which is the ONLY way to score
     *   SpeechRecognizer if the file feed isn't honored. It measures a different
     *   thing from the other engines (full acoustic chain: speaker, room, mic,
     *   and the device's own AGC/noise-suppression), so its WER is NOT directly
     *   comparable to the file-fed engines' — only to another engine measured
     *   through the same rig.
     */
    fun run(context: Context, wav: File, timeoutMs: Long = 60_000L,
            onDevice: Boolean = true, fedAudio: Boolean = true): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result("", -1, "DROP: EXTRA_AUDIO_SOURCE needs API 31+ " +
                "(this device is ${Build.VERSION.SDK_INT})")
        }
        var readSide: ParcelFileDescriptor? = null
        if (fedAudio) {
            val pcm = try {
                SttBenchRunner.readWavPcm(wav)      // header stripped — see KDoc
            } catch (e: Exception) {
                return Result("", -1, "DROP: ${e.message}")
            }
            // A pipe, not the file itself: the recognizer reads until EOF, and we
            // control when EOF lands. Writing must not block the main thread.
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            val writeSide = pipe[1]
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(pcm) }
                } catch (e: Exception) {
                    Log.w(TAG, "DROP: feeding PCM to SpeechRecognizer failed", e)
                }
            }.start()
        }

        val done = CountDownLatch(1)
        val transcript = AtomicReference("")
        val failure = AtomicReference<String?>(null)
        val errCode = AtomicLong(-1)
        val startNs = AtomicLong(0)
        val finalNs = AtomicLong(0)
        val which = AtomicReference("default")

        Handler(Looper.getMainLooper()).post {
            val recognizer = try {
                if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    which.set("on_device")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    which.set("default")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (t: Throwable) {
                failure.set("createSpeechRecognizer failed: ${t.message}")
                done.countDown()
                return@post
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (readSide != null) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                // Logged because the failure mode to distinguish is "recognizer
                // ignored the file and opened the mic" vs "recognizer never
                // engaged at all" — silence in both cases otherwise.
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "native: onReadyForSpeech (${which.get()})")
                }
                override fun onBeginningOfSpeech() {
                    Log.i(TAG, "native: onBeginningOfSpeech")
                }
                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { startNs.compareAndSet(0, System.nanoTime()) }
                override fun onPartialResults(partial: Bundle?) {}
                override fun onEvent(type: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    finalNs.set(System.nanoTime())
                    transcript.set(
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty())
                    done.countDown()
                }
                override fun onError(error: Int) {
                    errCode.set(error.toLong())
                    failure.set("recognizer error $error" +
                        if (looksLikeMicFallback(error))
                            " (NO_MATCH/TIMEOUT on fed audio — EXTRA_AUDIO_SOURCE likely ignored)"
                        else "")
                    done.countDown()
                }
            })
            // ⚠️ Do NOT close readSide here. startListening() MARSHALS the intent
            // asynchronously (it posts to SpeechRecognizer's handler), so closing
            // our copy right after the call hands Parcel a dead descriptor and
            // crashes the process with "Bad file descriptor" in
            // nativeWriteFileDescriptor. It stays open until the run completes.
            recognizer.startListening(intent)
        }

        val ok = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { readSide?.close() } catch (_: Exception) {}
        if (!ok) return Result("", -1, "timeout after ${timeoutMs}ms", which.get())

        // No mic here, so "end of speech" is EOF on the pipe; when the recognizer
        // never reports it, fall back to reporting no timing rather than a number
        // measured from the wrong anchor.
        val anchor = startNs.get()
        val ttft = if (anchor > 0 && finalNs.get() > 0)
            (finalNs.get() - anchor) / 1_000_000 else -1
        return Result(transcript.get(), ttft, failure.get(), which.get(), errCode.get().toInt())
    }
}

package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Android's built-in [SpeechRecognizer] as a real STT provider — the free fallback for devices
 * with Google services but no Home Assistant and no own-box Whisper (WS-D.1's missing rung;
 * `SttProviderFactory` used to map `STT_ANDROID_VOICE` onto HA Assist with a TODO).
 *
 * ⚠️ **This provider breaks the [SttProvider] streaming contract, and it has to.** Every other
 * provider is FED PCM from Dashie's shared capture (`streamAudio`). SpeechRecognizer has no API
 * to accept audio — it opens the microphone ITSELF. So:
 *
 *  - [streamAudio] is a deliberate no-op (the coordinator may still call it; the audio is
 *    already going to the recognizer's own AudioRecord).
 *  - [startSession] must first make Dashie RELEASE the mic, or the recognizer opens a second
 *    AudioRecord against a mic we already hold and (device-dependent) gets silence or fails.
 *    That handoff is exactly what entering Live mode already does — stop streaming, stop the
 *    wake detector, stop the capture service — and [micHandoff] performs it here.
 *  - Everything runs on the MAIN thread: SpeechRecognizer throws if created or driven from a
 *    background thread.
 *
 * Consequences worth knowing before relying on it:
 *  - **No AEC.** [com.dashieapp.Dashie.halite.voice.aec.CascadeAecController] processes Dashie's
 *    capture path; the recognizer's private mic bypasses it entirely. Fine here — wake detection
 *    (and therefore barge-in) is stopped for the duration of the session anyway.
 *  - **No interim-driven VAD.** The recognizer owns endpointing, so the coordinator's VAD and
 *    no-transcript timeout are advisory for this provider.
 *  - **Not available on Fire OS / Lineage** (no Google services) — [isAvailable] reports that
 *    honestly rather than pretending, which is what keeps the $0 ladder's dead-end case correct.
 */
class AndroidSpeechRecognizerProvider(
    private val context: Context,
    /** Releases Dashie's mic before listening, and restores capture + wake word after. */
    private val micHandoff: MicHandoff,
) : SttProvider {

    /** The coordinator-side mic ownership swap (see class KDoc). */
    interface MicHandoff {
        /** Stop Dashie's capture so the recognizer can open the mic. [onReleased] fires once the
         *  mic is ACTUALLY free (AudioRecord released) — the recognizer must NOT open its own mic
         *  until then, or a 2nd+ session opens mid-release and gets silence (device-confirmed
         *  2026-07-20). Implemented via AudioCaptureService.stopAndNotify (deterministic, no delay). */
        fun release(onReleased: () -> Unit)
        /** Restart capture + wake detection after the session ends. Must be idempotent. */
        fun restore()
    }

    companion object {
        private const val TAG = "AndroidSttProvider"
        /** Guard against a recognizer that never calls back (seen when a vendor implementation
         *  is present but broken) — without it the mic would stay handed off forever. */
        private const val WATCHDOG_MS = 15_000L
        /** If the mic-release signal is lost, start the recognizer anyway after this — so a wedged
         *  capture thread can never hang STT. Comfortably above a normal ~100ms release settle. */
        private const val RELEASE_SETTLE_FALLBACK_MS = 800L
    }

    override val providerId = "android_speech_recognizer"
    override val displayName = "Android Voice"

    /** The whole reason [micHandoff] exists — see the class KDoc and [SttProvider.ownsMic]. */
    override val ownsMic = true

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: SttListener? = null
    private var language: String = Locale.getDefault().toLanguageTag()
    /** One session's callbacks fire at most once; a recognizer can emit error AFTER results. */
    private var sessionDone = false
    /** The recognizer's mic is opened only AFTER Dashie's is released (release callback), and its
     *  fallback both target [beginRecognizer] — this makes it run exactly once. */
    private var recognizerBegun = false
    private var watchdog: Runnable? = null

    override fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (t: Throwable) {
        Log.w(TAG, "isRecognitionAvailable threw: ${t.message}")
        false
    }

    override suspend fun initialize(config: SttConfig): Boolean {
        // Nothing to configure ahead of time — the recognizer is created per session (a
        // long-lived instance goes stale across the app being backgrounded on some OEMs).
        return isAvailable()
    }

    override fun startSession(listener: SttListener) {
        main.post {
            // Reset session state HERE (on main), NOT synchronously. SttProviderManager.startSession
            // calls cancelSession() immediately before this, which POSTS the prior session's teardown
            // (sessionDone=true + onSessionEnded) to main. A synchronous reset ran BEFORE that posted
            // cancel, so the cancel — meant for the OLD session — clobbered sessionDone back to true and
            // fired onSessionEnded on the NEW listener; the deferred beginRecognizer then saw
            // sessionDone=true and never opened the mic (device-confirmed: turn 2 stuck in CONNECTING).
            // Resetting inside this post (which the queue orders AFTER the cancel) makes the reset win,
            // and lets the cancel end the OLD session on the OLD listener as intended.
            this.listener = listener
            sessionDone = false
            recognizerBegun = false
            if (!isAvailable()) {
                finish { it.onError("Speech recognition not available on this device", isRecoverable = false) }
                return@post
            }
            // Hand off the mic and WAIT for it to ACTUALLY release before opening the recognizer's
            // OWN mic — else a 2nd+ session opens while Dashie's AudioRecord is still releasing and
            // the recognizer captures silence (device-confirmed 2026-07-20). MUST precede
            // createSpeechRecognizer/startListening (see class KDoc). A fallback starts the
            // recognizer anyway if the release signal is lost, so a wedged capture can't hang STT.
            val fallback = Runnable {
                Log.w(TAG, "DROP: mic-release signal not seen in ${RELEASE_SETTLE_FALLBACK_MS}ms — starting recognizer anyway")
                beginRecognizer(listener)
            }
            main.postDelayed(fallback, RELEASE_SETTLE_FALLBACK_MS)
            micHandoff.release {
                main.post { main.removeCallbacks(fallback); beginRecognizer(listener) }
            }
        }
    }

    /** Open the recognizer's OWN mic + start listening — only after Dashie's mic is released.
     *  Idempotent (the release callback AND its fallback both call this) and a no-op if the session
     *  was cancelled while waiting. Runs on the main thread (SpeechRecognizer requires it). */
    private fun beginRecognizer(listener: SttListener) {
        if (recognizerBegun || sessionDone) return
        recognizerBegun = true
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (t: Throwable) {
            Log.e(TAG, "createSpeechRecognizer failed: ${t.message}")
            finish { it.onError("Speech recognizer unavailable", isRecoverable = false) }
            return
        }
        recognizer = r
        r.setRecognitionListener(recognitionListener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Prefer on-device recognition where the OEM supports it: this is the FREE
            // fallback, so keeping it off the network is both faster and more private.
            // Ignored on older/unsupporting devices (falls back to Google's cloud, which
            // still costs Dashie nothing).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            r.startListening(intent)
            armWatchdog()
            listener.onSessionStarted()
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed: ${t.message}")
            finish { it.onError("Couldn't start speech recognition", isRecoverable = true) }
        }
    }

    /** No-op by design — the recognizer captures its own audio (see class KDoc). */
    override fun streamAudio(audioData: ByteArray) { /* intentionally empty */ }

    /** The coordinator's VAD decided the utterance ended; ask the recognizer to finalize.
     *  It usually endpoints on its own first, so this is a backstop, not the main path. */
    override fun endAudioStream() {
        main.post { runCatching { recognizer?.stopListening() } }
    }

    override fun cancelSession() {
        main.post {
            cancelWatchdog()
            runCatching { recognizer?.cancel() }
            teardown()
            // A cancel produces no terminal callback, so release the session here.
            if (!sessionDone) { sessionDone = true; listener?.onSessionEnded() }
        }
    }

    override fun release() {
        main.post { cancelWatchdog(); teardown() }
    }

    // ── internals ────────────────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "ready for speech") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Log.d(TAG, "end of speech") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (!text.isNullOrBlank() && !sessionDone) listener?.onInterimResult(text)
        }

        override fun onResults(results: Bundle?) {
            cancelWatchdog()
            val text = firstResult(results)
            if (text.isNullOrBlank()) finish { it.onNoSpeechDetected() }
            else finish { it.onFinalResult(text) }
        }

        override fun onError(error: Int) {
            cancelWatchdog()
            // NO_MATCH / SPEECH_TIMEOUT are "the user said nothing", not failures — routing them
            // to onError would surface a red error card for an ordinary silent window.
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                finish { it.onNoSpeechDetected() }
                return
            }
            val recoverable = error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                error != SpeechRecognizer.ERROR_CLIENT
            Log.w(TAG, "recognition error $error (${describe(error)})")
            finish { it.onError("Speech recognition error: ${describe(error)}", recoverable) }
        }
    }

    /** Deliver ONE terminal callback, then always end the session and give the mic back.
     *  The mic MUST be restored on every exit path or wake word never comes back. */
    private fun finish(deliver: (SttListener) -> Unit) {
        if (sessionDone) return
        sessionDone = true
        val l = listener
        teardown()
        l?.let {
            runCatching { deliver(it) }
            runCatching { it.onSessionEnded() }
        }
    }

    private fun teardown() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { micHandoff.restore() }
    }

    private fun armWatchdog() {
        cancelWatchdog()
        watchdog = Runnable {
            Log.w(TAG, "DROP: recognizer produced no callback in ${WATCHDOG_MS}ms — releasing the mic")
            runCatching { recognizer?.cancel() }
            finish { it.onError("Speech recognition timed out", isRecoverable = true) }
        }.also { main.postDelayed(it, WATCHDOG_MS) }
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        else -> "code $error"
    }
}

package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.util.Log
import com.dashieapp.Dashie.audio.AudioCaptureService
import com.dashieapp.Dashie.halite.voice.stt.AndroidSpeechRecognizerProvider
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface

/**
 * Hands Dashie's shared microphone to a provider that captures its OWN audio
 * ([AndroidSpeechRecognizerProvider]), then takes it back.
 *
 * 🔴 **Why this is one class and not two objects.** Both voice pipelines need this swap and the
 * bodies are near-identical — stop the wake detector, release capture deterministically, restart
 * capture after. They differ in **exactly one decision**, and that decision is not cosmetic:
 *
 * | | cascade ([VoicePipelineCoordinator]) | HA Voice Assist ([HaliteVoiceController]) |
 * |---|---|---|
 * | when STT ends, the turn is… | **over** | **half done** — transcript still goes to HA for intent + a spoken reply |
 * | so [restore] must… | restart capture **and re-arm the wake word** | restart capture **only** |
 *
 * Re-arming in the HA-Assist case would leave the detector live through HA's own TTS reply and
 * Dashie would hear itself — the failure the cascade avoids with its `!inConversation` guard.
 * `HaVoiceService` re-arms on all three of its exits (complete / error / cancel), so the wake word
 * is not lost by leaving it alone; it is correctly timed by the object that knows the turn is over.
 *
 * Written as ONE parameterized class (Standing Rule 1) precisely because that difference is the
 * interesting part: side by side it is a documented row in the table above, whereas as two
 * hand-written objects it was a KDoc in one file asking the reader to go and compare the other.
 *
 * Everything is passed as a lambda rather than a reference because both owners rebuild their
 * capture/detector across a voice re-init — see `stale_webview_refs`, same class of bug.
 */
class SharedCaptureMicHandoff(
    private val capture: () -> AudioCaptureService?,
    private val wakeWord: () -> WakeWordDetectorInterface?,
    private val handler: Handler,
    /**
     * Guard evaluated after [REARM_SETTLE_MS] to decide whether to re-arm the wake detector in
     * [restore] — or **null to never re-arm**, which is the HA Voice Assist contract above.
     *
     * A predicate rather than a Boolean: it is read AFTER the settle delay, by which time the
     * pipeline may have been disabled or entered a conversation.
     */
    private val reArmWakeWord: (() -> Boolean)?,
    private val tag: String,
    /** Extra teardown before the mic is released — the cascade stops its shared-buffer streaming
     *  loop here. HA Voice Assist's stage has no such loop, hence the default. */
    private val beforeRelease: (() -> Unit)? = null,
    /** Skip [restore] entirely (pipeline disabled mid-turn). Default: always restore. */
    private val canRestore: () -> Boolean = { true },
) : AndroidSpeechRecognizerProvider.MicHandoff {

    companion object {
        /**
         * The recognizer's own AudioRecord must actually be released before ours re-opens, or the
         * wake word comes back deaf. Matches the cascade's `resumeAfterConversation` settle —
         * this value was measured there, not guessed here.
         */
        private const val REARM_SETTLE_MS = 400L
    }

    override fun release(onReleased: () -> Unit) {
        beforeRelease?.invoke()
        wakeWord()?.stop()
        // stopAndNotify, not stop(): the recognizer must NOT open its mic until ours is genuinely
        // gone, or a 2nd+ session opens mid-release and captures silence (device-confirmed
        // 2026-07-20). The elvis keeps a missing capture from stranding the handoff forever.
        capture()?.stopAndNotify(onReleased) ?: onReleased()
    }

    override fun restore() {
        if (!canRestore()) return
        capture()?.start()   // idempotent — no-ops if already running
        val guard = reArmWakeWord ?: run {
            Log.d(tag, "Mic restored (capture only) — the turn owner re-arms the wake word")
            return
        }
        handler.postDelayed({ if (guard()) wakeWord()?.start() }, REARM_SETTLE_MS)
    }
}

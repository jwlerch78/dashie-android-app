package com.dashieapp.Dashie.voice.realtime

/**
 * Engine-agnostic contract for realtime "conversation mode" (build plan
 * 20260625_REALTIME_VOICE_CONVERSATION_MODE.md §3.8). A shared layer (trigger,
 * lifecycle, mic hand-off, indicator) drives one of two pluggable engines:
 *   • [GeminiLiveEngine] — Engine A, cloud S2S over the conversation-relay (Phase 1).
 *   • a future local-cascade engine — Engine B (Phase 3).
 *
 * Building Phase 1 against this interface is the cheap insurance that makes the
 * local engine additive later instead of a rewrite. The engine owns the
 * audio→response loop; the caller owns *when* a session starts/stops.
 */
interface ConversationEngine {

    /** Open a session and begin full-duplex audio. Idempotent-safe: a second
     *  call while active is ignored. The engine resolves nothing async itself —
     *  the caller supplies a ready [RealtimeConfig] (incl. a fresh JWT).
     *
     *  [deferMic] = open the socket + run setup but DON'T start the mic or emit
     *  state yet — used for a *speculative* connect in parallel with STT, so the
     *  channel is warm by the time we know it's a conversational query. Call
     *  [beginWithText] to commit (start the mic + send the captured first turn). */
    fun start(config: RealtimeConfig, listener: Listener, deferMic: Boolean = false)

    /**
     * Commit a deferred session: start the mic and (optionally) send [text] as the first turn.
     *
     * @return false when the session could not be committed because the socket is no longer
     *   usable — the caller must then fall back (open a fresh session) rather than assume the
     *   utterance was delivered. Engines that do not defer return true.
     *
     * ⚠️ The return value exists because `isActive` alone is racy: the relay can close between
     * a caller's liveness check and this call, and the close is handled on a posted main-thread
     * task. Observed live 2026-08-01 — Google closed 1011 (quota), the cascade handed the
     * utterance to the dead engine, and the user got DEAD AIR: no reply, no error, no retry,
     * findable only in logcat.
     */
    fun beginWithText(text: String?): Boolean = true

    /** End the session and release the mic/speaker. Safe to call when idle. */
    fun stop()

    /** True between [start] and [stop]/close. */
    val isActive: Boolean

    /** True when the most recent close was a quiet idle timeout (no follow-up)
     *  rather than a manual stop, error, or end_conversation. The UI uses this to
     *  leave the last response on screen instead of tearing the panel down. */
    val closedByIdle: Boolean get() = false

    /** Coarse session state for the indicator UX (#5) and the lifecycle (#3). */
    enum class State { CONNECTING, LISTENING, SPEAKING, INTERRUPTED, CLOSED, ERROR }

    /** Who is talking, for the on-screen conversation transcript. */
    enum class Speaker { USER, DASHIE }

    /** All callbacks fire on the main thread. */
    interface Listener {
        fun onStateChanged(state: State)
        /** Live conversation transcript for on-screen display. `text` is the
         *  running accumulated text for the current turn; `isFinal` marks the
         *  turn complete. (Also logged server-side by the relay for the console.) */
        fun onTranscript(speaker: Speaker, text: String, isFinal: Boolean) {}
        /** Human-readable per-turn usage line for optional on-screen display.
         *  Authoritative billing happens server-side in the relay (#6) — this is
         *  display only. */
        fun onUsage(summary: String) {}
        /** A registry tool (run server-side by the relay) produced a card to display —
         *  e.g. a sports scorecard. JSON payload (`type` discriminates the renderer).
         *  Channels without a screen ignore it. */
        fun onCard(card: org.json.JSONObject) {}
        /** Verbose line for dev/debug surfaces. */
        fun onLog(line: String) {}
        fun onError(message: String) {}
    }
}

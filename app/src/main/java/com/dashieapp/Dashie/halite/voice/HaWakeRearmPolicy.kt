package com.dashieapp.Dashie.halite.voice

/**
 * WHEN the HA-Assist path may bring the wake detector back mid-turn.
 *
 * ## Why this is a separate, pure object
 *
 * Same reason as `MicroWakeWordRearmPolicy` and `DetectionCooldown`: it is a rule that was **wrong
 * on a device and unassertable off one**. The rule lived as a `stop()` at the top of the interaction
 * and a `postDelayed(start, 500)` at the bottom — reachable only with a microphone, a Home Assistant
 * WebSocket and real TTS playback. So it stayed wrong for ~8 months and was found by a person
 * talking to a tablet.
 *
 * ## The rule, and the measurement behind it
 *
 * Recording is the only phase that needs the detector down: while the user is being transcribed, a
 * wake fire would be self-triggering. Everything after that — HA thinking, HA speaking — is exactly
 * when a person interrupts, so the detector belongs back up.
 *
 * 🔴 Measured on device 2026-08-26 before the fix: nothing listened for **5.15–5.18 s** (4/4), the
 * whole interaction plus a 502 ms tail. Barge-in was structurally unreachable in this mode — the
 * `BARGE:` gate existed and no audio ever arrived at it.
 *
 * The mode next door (`VoicePipelineCoordinator`) already stops only *"during STT recording"* and
 * restarts the instant a transcript lands, *"so user can interrupt during PROCESSING or SPEAKING"*.
 * This states that same rule where it can be tested.
 */
internal object HaWakeRearmPolicy {

    /**
     * The one set this object exists to define: **mid-turn states in which the user may interrupt.**
     *
     * A turn is interruptible once recording is over — HA thinking (`PROCESSING`) and HA speaking
     * (`SPEAKING`). It is not interruptible while the microphone is being transcribed (`LISTENING`)
     * or while the socket is coming up (`CONNECTING`), and `IDLE`/`ERROR` are not mid-turn at all.
     *
     * Two call sites consume this, which is why it is a named set and not an `if` in either of them:
     *  1. [shouldRearmOnEntering] — on entering such a state, bring the wake detector back up.
     *  2. `HaVoiceService.onWakeWordDetected` — a wake arriving IN such a state is honoured and
     *     interrupts the turn, instead of being discarded.
     *
     * 🔴 Both were separately broken and each hid the next: nothing listened (fixed 2026-08-26),
     * the TTS-window gate asked the wrong coordinator (same day), and then the wake that survived
     * both was thrown away by a state guard — measured 3/3 including a 100 % fire, so a threshold
     * was never the cause at any layer.
     */
    fun isInterruptible(state: HaVoiceService.VoiceState): Boolean = when (state) {
        HaVoiceService.VoiceState.PROCESSING,
        HaVoiceService.VoiceState.SPEAKING -> true

        HaVoiceService.VoiceState.IDLE,
        HaVoiceService.VoiceState.CONNECTING,
        HaVoiceService.VoiceState.LISTENING,
        HaVoiceService.VoiceState.ERROR -> false
    }

    /**
     * True when a transition INTO [state] means recording is over and the user may interrupt.
     *
     * ⚠️ `LISTENING` must be false — re-arming while the microphone is being transcribed is the
     * self-trigger this whole stop/start dance exists to prevent.
     *
     * ⚠️ `IDLE`/`ERROR` are false here on purpose, and that is not "no re-arm": those are the
     * END of a turn and keep the long-standing completion-path restart. Returning true would put
     * two restarts on the same transition.
     *
     * ⚠️ **This is NOT the whole wake-ACCEPTANCE rule, and the difference is `IDLE`.** A wake in
     * `IDLE` is the ordinary way a turn starts and must always be accepted, while re-arming on
     * entering `IDLE` would double the completion-path restart. So the acceptance guard reads
     * `IDLE || isInterruptible(state)` — using this predicate alone as the guard would reject
     * every wake in `IDLE` and kill voice entirely.
     */
    fun shouldRearmOnEntering(state: HaVoiceService.VoiceState): Boolean = isInterruptible(state)
}

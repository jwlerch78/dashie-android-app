package com.dashieapp.Dashie.halite.voice

/**
 * Pure, stateless re-arm policy for a completed COMMAND turn: "should the mic open again?"
 *
 * This exists because the answer is decided in **two independent lanes** that are easy to conflate,
 * and conflating them silently disables a user setting (field bug, 2026-07-18):
 *
 *  • **DLG-6 / non-dialog lane** — [shouldReArmNonDialog] → `reArmPrimaryListen()`. The auto-wake
 *    re-open of the PRIMARY listen after a local command completes outside any dialog. It is false
 *    *structurally* whenever a dialog is active, because the dialog loop owns re-listening there.
 *
 *  • **Cascade dialog lane** — [shouldReListenAfterDialogCommand] → `cascadeReListen()`. A command
 *    executed while the dialog panel is already open.
 *
 * [keepOpenAfterCommand] is the **shared user policy** both lanes consult. It is deliberately
 * lane-independent: the dialog lane MUST NOT read [shouldReArmNonDialog], whose `dialogActive`
 * guard makes it *always* false inside a dialog. Reading that structural false as "the user said
 * no" ends every in-dialog command turn even with "keep dialog open" ON — which is exactly the bug
 * this file was extracted to make unrepresentable.
 *
 * **Scope: COMMAND turns only.** A question/answer turn in a dialog always re-listens and never
 * consults this — conversation mode is not gated on the keep-open pref.
 *
 * End-intent vocabulary comes from [CascadeDialogSupport.isEndIntent], which is codegen'd from
 * `dialog-policy.ts` so it can't drift (contract #5).
 */
object ReArmPolicy {

    /**
     * The USER'S policy for a command turn: DLG-6 "keep dialog open" is on AND this utterance is
     * not an end-intent ("never mind" / "stop" / "shut up").
     *
     * The end-intent check must happen locally: in DLG-6 an end phrase is a "silent command" that
     * never reaches the brain, so `metadata.end_conversation` can't cover it (contract #5).
     */
    fun keepOpenAfterCommand(alwaysOpenDialog: Boolean, transcript: String): Boolean =
        alwaysOpenDialog && !CascadeDialogSupport.isEndIntent(transcript)

    /**
     * DLG-6 lane: re-arm the primary listen after this local command?
     *
     * Adds the non-dialog lane's structural guards to [keepOpenAfterCommand]:
     *  - voice must be enabled and the mic live,
     *  - no dialog may be active (the dialog loop owns re-listening — see [shouldReListenAfterDialogCommand]),
     *  - single-shot mode never chains,
     *  - and a turn that started audible playback never re-arms ([startedMedia]).
     *
     * [startedMedia] is a required parameter rather than a caller-side `&&` so that no call site can
     * re-arm into a track it just started by forgetting the check.
     */
    fun shouldReArmNonDialog(
        enabled: Boolean,
        micMuted: Boolean,
        dialogActive: Boolean,
        singleMode: Boolean,
        alwaysOpenDialog: Boolean,
        transcript: String,
        startedMedia: Boolean = false,
    ): Boolean {
        if (!enabled || micMuted || dialogActive) return false
        if (singleMode) return false
        if (startedMedia) return false
        return keepOpenAfterCommand(alwaysOpenDialog, transcript)
    }

    /**
     * Cascade dialog lane: keep the loop alive after a command executed inside an open dialog?
     *
     * Consults [keepOpenAfterCommand] — the user policy — and NOT [shouldReArmNonDialog], which is
     * structurally false here (see the class KDoc).
     *
     * [startedMedia] vetoes unconditionally: "keep dialog open" chains SILENT commands, and no
     * setting makes re-listening into the track we just started correct — 600ms later the mic
     * would hear the song. (Field 2026-07-18: "turn off the lights and play the beatles" re-opened
     * the mic onto the Beatles and fired a second brain turn 14s later.)
     */
    fun shouldReListenAfterDialogCommand(
        alwaysOpenDialog: Boolean,
        transcript: String,
        startedMedia: Boolean,
    ): Boolean {
        if (startedMedia) return false
        return keepOpenAfterCommand(alwaysOpenDialog, transcript)
    }

    /** OFF-direction words: a command containing one is powering media DOWN, not starting it. */
    private val HA_OFF_DIRECTION = Regex("""\b(off|stop|pause|mute|quiet|silence)\b""", RegexOption.IGNORE_CASE)

    /** Media-device nouns for the lane with NO structured result (the JS-bridge in-dialog lane).
     *  Native-only re-arm policy vocabulary — not a mirror of the JS classifier's transport lists
     *  (those detect play/pause/skip verbs; this detects the DEVICE a power command targets). */
    private val HA_MEDIA_DEVICE = Regex(
        """\b(tv|television|stereo|speakers?|radio|receiver|soundbar|record player|turntable|jukebox|projector)\b""",
        RegexOption.IGNORE_CASE)

    /**
     * Did this HA command plausibly START audible playback ("turn on the TV")? Feeds [startedMedia]
     * on the HA lanes — the same veto MusicVoiceTool.startsAudiblePlayback provides for music.
     *
     * [targetedMediaPlayer] is the structured signal when the executor has one
     * (HaAssistConverse.Result.mediaTargeted — a `media_player.*` entity was actually touched);
     * pass null when only the transcript is available and the device-noun heuristic decides.
     * The transcript OFF-direction guard applies in both cases: turning a TV off is silent.
     */
    fun haCommandStartsMedia(transcript: String, targetedMediaPlayer: Boolean? = null): Boolean {
        if (HA_OFF_DIRECTION.containsMatchIn(transcript)) return false
        return targetedMediaPlayer ?: HA_MEDIA_DEVICE.containsMatchIn(transcript)
    }
}

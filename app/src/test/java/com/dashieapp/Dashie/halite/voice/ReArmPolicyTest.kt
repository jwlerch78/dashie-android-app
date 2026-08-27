package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the re-arm decision — the first tests on this code, which until now was
 * verified only by on-device smoke and has been the source of two field bugs (2026-07-18):
 * the dialog lane silently ignoring "keep dialog open", and the mic re-arming onto a track it
 * had just started.
 *
 * The lane-confusion trap is a test case here, not just a comment.
 */
class ReArmPolicyTest {

    private val COMMAND = "turn off the string lights"

    // ── The shared user policy ───────────────────────────────────────────────

    @Test
    fun `keepOpen is off when the pref is off`() {
        assertFalse(ReArmPolicy.keepOpenAfterCommand(alwaysOpenDialog = false, transcript = COMMAND))
    }

    @Test
    fun `keepOpen is on when the pref is on`() {
        assertTrue(ReArmPolicy.keepOpenAfterCommand(alwaysOpenDialog = true, transcript = COMMAND))
    }

    @Test
    fun `an end-intent closes even with the pref on`() {
        // DLG-6: "never mind" is a local silent command that never reaches the brain, so
        // metadata.end_conversation can't cover it (contract #5).
        assertFalse(ReArmPolicy.keepOpenAfterCommand(alwaysOpenDialog = true, transcript = "never mind"))
        assertFalse(ReArmPolicy.keepOpenAfterCommand(alwaysOpenDialog = true, transcript = "thanks"))
        assertFalse(ReArmPolicy.keepOpenAfterCommand(alwaysOpenDialog = true, transcript = "shut up"))
    }

    @Test
    fun `a wake-prefixed end-intent still closes`() {
        assertFalse(ReArmPolicy.keepOpenAfterCommand(alwaysOpenDialog = true, transcript = "hey dashie, never mind"))
    }

    // ── DLG-6 / non-dialog lane ──────────────────────────────────────────────

    private fun nonDialog(
        enabled: Boolean = true,
        micMuted: Boolean = false,
        dialogActive: Boolean = false,
        singleMode: Boolean = false,
        alwaysOpenDialog: Boolean = true,
        transcript: String = COMMAND,
        startedMedia: Boolean = false,
    ) = ReArmPolicy.shouldReArmNonDialog(
        enabled, micMuted, dialogActive, singleMode, alwaysOpenDialog, transcript, startedMedia,
    )

    @Test
    fun `non-dialog lane re-arms a plain command with the pref on`() {
        assertTrue(nonDialog())
    }

    @Test
    fun `non-dialog lane respects the structural guards`() {
        assertFalse("voice disabled", nonDialog(enabled = false))
        assertFalse("mic muted", nonDialog(micMuted = true))
        assertFalse("single-shot mode never chains", nonDialog(singleMode = true))
        assertFalse("pref off", nonDialog(alwaysOpenDialog = false))
    }

    @Test
    fun `non-dialog lane is structurally false inside a dialog`() {
        // NOT a policy signal — the dialog loop owns re-listening there. This is the value the
        // dialog lane must never read as "the user said no". See the paired test below.
        assertFalse(nonDialog(dialogActive = true, alwaysOpenDialog = true))
    }

    // ── Cascade dialog lane ──────────────────────────────────────────────────

    @Test
    fun `dialog lane keeps the loop alive with the pref on`() {
        assertTrue(
            ReArmPolicy.shouldReListenAfterDialogCommand(
                alwaysOpenDialog = true, transcript = COMMAND, startedMedia = false,
            )
        )
    }

    @Test
    fun `dialog lane ends the turn with the pref off`() {
        assertFalse(
            ReArmPolicy.shouldReListenAfterDialogCommand(
                alwaysOpenDialog = true.not(), transcript = COMMAND, startedMedia = false,
            )
        )
    }

    /**
     * THE TRAP. Same inputs, opposite answers — and that is correct.
     *
     * Inside an active dialog with "keep dialog open" ON, the non-dialog lane returns false
     * (structural: the dialog loop owns re-listening) while the dialog lane returns true (the user
     * asked to chain). Gating the dialog lane on the non-dialog lane would end every in-dialog
     * command turn even with the pref ON — silently killing the setting. That was the field bug.
     */
    @Test
    fun `the two lanes disagree inside a dialog and that is the point`() {
        val nonDialogAnswer = nonDialog(dialogActive = true, alwaysOpenDialog = true)
        val dialogAnswer = ReArmPolicy.shouldReListenAfterDialogCommand(
            alwaysOpenDialog = true, transcript = COMMAND, startedMedia = false,
        )
        assertFalse("non-dialog lane is structurally false in a dialog", nonDialogAnswer)
        assertTrue("dialog lane honors the user's pref", dialogAnswer)
    }

    // ── The media veto ───────────────────────────────────────────────────────

    @Test
    fun `started media vetoes the dialog lane regardless of the pref`() {
        // No setting makes re-listening into a playing track correct — 600ms later the mic hears
        // the song. Field 2026-07-18: a second brain turn fired 14s later, answering the Beatles.
        assertFalse(
            ReArmPolicy.shouldReListenAfterDialogCommand(
                alwaysOpenDialog = true, transcript = COMMAND, startedMedia = true,
            )
        )
    }

    @Test
    fun `started media vetoes the non-dialog lane regardless of the pref`() {
        assertFalse(nonDialog(alwaysOpenDialog = true, startedMedia = true))
    }

    @Test
    fun `a transport command that leaves the room silent still chains`() {
        // pause / volume / "what's playing" don't start audible playback, so they honor the pref.
        assertTrue(nonDialog(alwaysOpenDialog = true, transcript = "pause the music", startedMedia = false))
    }

    // ── HA media detection (feeds startedMedia on the HA lanes) ──────────────

    @Test
    fun `structured signal wins - media_player touched and not an off command`() {
        assertTrue(ReArmPolicy.haCommandStartsMedia("turn on the living room tv", targetedMediaPlayer = true))
        // Structured "no media_player" is trusted even when the transcript sounds media-ish
        // ("turn on the tv backlight" resolves to a light).
        assertFalse(ReArmPolicy.haCommandStartsMedia("turn on the tv backlight", targetedMediaPlayer = false))
    }

    @Test
    fun `off-direction commands are silent regardless of target`() {
        // Turning a TV OFF ends room audio — re-arming after it is fine.
        assertFalse(ReArmPolicy.haCommandStartsMedia("turn off the tv", targetedMediaPlayer = true))
        assertFalse(ReArmPolicy.haCommandStartsMedia("mute the living room speaker", targetedMediaPlayer = true))
        assertFalse(ReArmPolicy.haCommandStartsMedia("stop the music in the kitchen", targetedMediaPlayer = true))
    }

    @Test
    fun `transcript heuristic covers the lane with no structured result`() {
        // executeHaCommandInDialog runs via the JS bridge — only the transcript is available.
        assertTrue(ReArmPolicy.haCommandStartsMedia("turn on the tv"))
        assertTrue(ReArmPolicy.haCommandStartsMedia("turn on the kitchen speaker"))
        assertFalse(ReArmPolicy.haCommandStartsMedia("turn on the string lights"))
        assertFalse(ReArmPolicy.haCommandStartsMedia("lock the front door"))
    }
}

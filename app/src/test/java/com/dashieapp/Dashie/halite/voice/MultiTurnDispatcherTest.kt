package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [MultiTurnDispatcher.reconcileVoice] — the per-step over-claim repair. The brain commits
 * the covering confirmation BEFORE the steps run ("Lights off and playing jazz!"), so a step that
 * fails or arrives `unsupported_tool` must not be spoken as done (the multi-emission field gap,
 * 2026-07-18: an unresolvable HA leg was skipped but the line still claimed it).
 */
class MultiTurnDispatcherTest {

    private fun summary(executed: Int, vararg unfulfilled: String) =
        MultiTurnDispatcher.Summary(executed = executed, unfulfilled = unfulfilled.toList())

    @Test
    fun `all steps ran - the brain's line is spoken as-is`() {
        assertEquals(
            "Lights off and playing jazz!",
            MultiTurnDispatcher.reconcileVoice("Lights off and playing jazz!", summary(2)),
        )
    }

    @Test
    fun `a failed leg gets an honest qualifier`() {
        assertEquals(
            "Lights off and playing jazz! Though the music part didn't go through.",
            MultiTurnDispatcher.reconcileVoice("Lights off and playing jazz!", summary(1, "music")),
        )
    }

    @Test
    fun `tool names are spoken, not their enum values`() {
        assertEquals(
            "Done! Though the home-control part didn't go through.",
            MultiTurnDispatcher.reconcileVoice("Done!", summary(1, "home_assistant")),
        )
    }

    @Test
    fun `several failed legs join into one qualifier`() {
        assertEquals(
            "Done! Though the home-control and camera parts didn't go through.",
            MultiTurnDispatcher.reconcileVoice("Done!", summary(1, "home_assistant", "video_feeds")),
        )
    }

    @Test
    fun `duplicate failed tools are mentioned once`() {
        assertEquals(
            "Done! Though the home-control part didn't go through.",
            MultiTurnDispatcher.reconcileVoice("Done!", summary(1, "home_assistant", "home_assistant")),
        )
    }

    @Test
    fun `nothing ran - the claim is replaced, not qualified`() {
        assertEquals(
            "Sorry — I couldn't get that done.",
            MultiTurnDispatcher.reconcileVoice("Lights off and playing jazz!", summary(0, "home_assistant", "music")),
        )
    }

    @Test
    fun `an unknown tool name still reads as words`() {
        assertEquals(
            "Done! Though the some tool part didn't go through.",
            MultiTurnDispatcher.reconcileVoice("Done!", summary(1, "some_tool")),
        )
    }

    @Test
    fun `a blank brain line falls back to Done`() {
        assertEquals(
            "Done. Though the music part didn't go through.",
            MultiTurnDispatcher.reconcileVoice("  ", summary(1, "music")),
        )
    }
}

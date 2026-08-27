package com.dashieapp.Dashie.halite.voice.lease

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the state model behind the household key-sharing sentence.
 *
 * The distinction these tests exist for is the one that is easy to lose in a refactor: **"the
 * lease does not govern this device" is not "the household refused you."** Collapsing them costs
 * nothing at compile time and produces a sentence that is false but plausible — the failure mode
 * that survives review.
 */
class LeaseStateHolderTest {

    @After fun tearDown() = LeaseStateHolder.resetForTest()

    @Test
    fun `nothing observed yet is null, not a state`() {
        LeaseStateHolder.resetForTest()
        assertNull(
            "Before the first renewal there is genuinely no state. Reporting one would be an " +
                "invention, and reporting REFUSED would be a lie.",
            LeaseStateHolder.snapshot
        )
    }

    @Test
    fun `ai present means the household is lending its keys`() {
        LeaseStateHolder.onGranted(emptyList())
        assertEquals(LeaseStateHolder.Share.USING_KEYS, LeaseStateHolder.snapshot?.share)
    }

    @Test
    fun `ai withheld as not-configured and as sharing-off are DIFFERENT states`() {
        // The pair that carries the design: identical in `capabilities`, opposite user actions.
        LeaseStateHolder.onGranted(listOf("ai"), mapOf("ai" to "capability_unavailable"))
        val noKeys = LeaseStateHolder.snapshot?.share
        LeaseStateHolder.onGranted(listOf("ai"), mapOf("ai" to "sharing_disabled"))
        val sharingOff = LeaseStateHolder.snapshot?.share

        assertEquals(LeaseStateHolder.Share.NO_KEYS_CONFIGURED, noKeys)
        assertEquals(LeaseStateHolder.Share.SHARING_OFF, sharingOff)
        org.junit.Assert.assertNotEquals(
            "'add a key' and 'turn sharing on' are opposite instructions — collapsing these " +
                "sends a user with no API key hunting for a toggle that was never the problem.",
            noKeys, sharingOff
        )
    }

    @Test
    fun `an ABSENT reason falls back to free-engines, never to no-keys`() {
        // 🔴 The compat case. Add-ons older than #70 send no `withheld` at all, and inferring
        // "no keys set up" from silence would tell a healthy household to configure something
        // that is already configured.
        LeaseStateHolder.onGranted(listOf("ai"))
        assertEquals(
            "Absent reason means UNKNOWN, and the honest fallback is the reason-free sentence — " +
                "which is also true, because the device really is on free engines.",
            LeaseStateHolder.Share.FREE_ENGINES_ONLY, LeaseStateHolder.snapshot?.share
        )
    }

    @Test
    fun `an UNRECOGNISED reason also falls back rather than guessing`() {
        LeaseStateHolder.onGranted(listOf("ai"), mapOf("ai" to "some_future_reason"))
        assertEquals(LeaseStateHolder.Share.FREE_ENGINES_ONLY, LeaseStateHolder.snapshot?.share)
    }

    @Test
    fun `NOT_APPLICABLE is distinct from REFUSED — the whole point of four states`() {
        LeaseStateHolder.onNotApplicable()
        val notApplicable = LeaseStateHolder.snapshot?.share
        LeaseStateHolder.onRefused()
        val refused = LeaseStateHolder.snapshot?.share

        assertEquals(LeaseStateHolder.Share.NOT_APPLICABLE, notApplicable)
        assertEquals(LeaseStateHolder.Share.REFUSED, refused)
        // Stated as its own assertion because the regression is someone "simplifying" the enum:
        // an HA-Assist device would then report that the household refused it, which is false —
        // nothing was withheld and nothing was asked.
        org.junit.Assert.assertNotEquals(
            "HA-Assist (lease does not govern this lane) must never collapse into REFUSED.",
            notApplicable, refused
        )
    }

    @Test
    fun `a later transition fully replaces the earlier one`() {
        LeaseStateHolder.onGranted(listOf("ai"), mapOf("ai" to "sharing_disabled"))
        LeaseStateHolder.onGranted(emptyList())
        // The snapshot is a whole-value replacement, so a stale `withheld` cannot survive a
        // transition that no longer withholds anything — the torn-read class this shape prevents.
        assertEquals(LeaseStateHolder.Share.USING_KEYS, LeaseStateHolder.snapshot?.share)
        assertEquals(emptyList<String>(), LeaseStateHolder.snapshot?.withheld)
    }
}

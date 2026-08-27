package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.voice.BrainRouteSelfHeal.Action
import com.dashieapp.Dashie.halite.voice.BrainRouteSelfHeal.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the brain-route self-heal DECISION (pure logic; no Android, no Robolectric).
 *
 * These lock down the safety rules that make the self-heal correct — especially the load-bearing
 * one: a re-probe that still says local must NOT fall back to cloud (else a real local-brain
 * household is silently yanked to cloud and billed for it).
 */
class BrainRouteSelfHealTest {

    // ── decide(): should we attempt a self-heal at all? ───────────────────────────────

    @Test
    fun `local turn that is unreachable triggers a reprobe`() {
        assertEquals(
            Action.REPROBE_THEN_MAYBE_RETRY,
            BrainRouteSelfHeal.decide(routedLocal = true, failure = FailureKind.LOCAL_UNREACHABLE, attempt = 0),
        )
    }

    @Test
    fun `a cloud failure does NOT self-heal — that is a Dashie outage, not a stale route`() {
        // Re-probing wouldn't help and could mask a real cloud outage.
        assertEquals(
            Action.NONE,
            BrainRouteSelfHeal.decide(routedLocal = false, failure = FailureKind.CLOUD_FAILURE, attempt = 0),
        )
    }

    @Test
    fun `a successful turn never triggers anything`() {
        assertEquals(
            Action.NONE,
            BrainRouteSelfHeal.decide(routedLocal = true, failure = FailureKind.NONE, attempt = 0),
        )
    }

    @Test
    fun `a cloud-routed turn that fails is not our signal (routedLocal=false)`() {
        assertEquals(
            Action.NONE,
            BrainRouteSelfHeal.decide(routedLocal = false, failure = FailureKind.LOCAL_UNREACHABLE, attempt = 0),
        )
    }

    @Test
    fun `the retry attempt never self-heals again — no infinite loop`() {
        // attempt=1 is the post-heal retry. Even if it fails local-unreachable again, give up.
        assertEquals(
            Action.NONE,
            BrainRouteSelfHeal.decide(routedLocal = true, failure = FailureKind.LOCAL_UNREACHABLE, attempt = 1),
        )
    }

    // ── decideAfterReprobe(): the load-bearing safety rule ────────────────────────────

    @Test
    fun `reprobe says cloud - retry the turn on cloud`() {
        assertTrue(BrainRouteSelfHeal.decideAfterReprobe(VoicePreferences.BRAIN_ROUTE_CLOUD))
    }

    @Test
    fun `reprobe still says local - do NOT retry on cloud (defer to the add-on, never assume cloud)`() {
        // THE load-bearing case: a genuine local household with a momentary hiccup must stay local,
        // not get yanked to cloud and billed. The add-on's fresh answer is authoritative.
        assertFalse(BrainRouteSelfHeal.decideAfterReprobe(VoicePreferences.BRAIN_ROUTE_LOCAL))
    }

    @Test
    fun `reprobe unreported (empty) - do NOT retry on cloud`() {
        // An unreported route (probe failed, or field absent) is NOT a license to assume cloud.
        assertFalse(BrainRouteSelfHeal.decideAfterReprobe(""))
    }

    @Test
    fun `reprobe garbage - do NOT retry on cloud`() {
        assertFalse(BrainRouteSelfHeal.decideAfterReprobe("banana"))
    }
}

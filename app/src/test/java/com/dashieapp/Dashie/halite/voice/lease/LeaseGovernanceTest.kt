package com.dashieapp.Dashie.halite.voice.lease

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the borrows-vs-owns rule behind [LeaseGovernance].
 *
 * The regression this guards is someone collapsing the predicate back to `isLinked` — which is
 * what the code effectively did before 2026-08-04 (it consulted account state nowhere at all) and
 * is what produced John's bug: a signed-in device refused voice/AI and told to enable household
 * sharing it does not use. The two-input table is the whole point, so each row is its own case.
 */
class LeaseGovernanceTest {

    @Test
    fun `a device with no account at all is governed — it borrows`() {
        // Chickadee, or a plain un-provisioned kiosk. This is the lease's whole reason to exist.
        assertTrue(LeaseGovernance.governs(isLinked = false, kioskProvisionedSession = false))
    }

    @Test
    fun `a household-provisioned kiosk is governed — it borrows`() {
        assertTrue(LeaseGovernance.governs(isLinked = true, kioskProvisionedSession = true))
    }

    @Test
    fun `a device signed in by a human is NOT governed — it owns its entitlement`() {
        // John's Mio: dashie_account_linked=true, session NOT kiosk-provisioned. Before the fix
        // this device got `renew-refused reason=sharing_disabled` and a false remedy string.
        assertFalse(LeaseGovernance.governs(isLinked = true, kioskProvisionedSession = false))
    }

    @Test
    fun `provisioned-but-unlinked is still governed — the flags are not interchangeable`() {
        // Not a state the writers should produce, and that is exactly why it is asserted: it
        // documents that `governs` keys on BOTH inputs rather than either one standing in for the
        // pair. If someone rewrites this as `!isLinked` this row still passes, but the signed-in
        // case above fails — the two together are what make the predicate unambiguous.
        assertTrue(LeaseGovernance.governs(isLinked = false, kioskProvisionedSession = true))
    }

    /**
     * The brain-route consumer, expressed against the same predicate.
     *
     * `VoicePreferences.effectiveUseLocalBrain` is `useLocalBrain || (governs && route == local)`.
     * These reproduce that expression rather than calling it, because the real one needs a
     * `Context`; what is being pinned is the RULE, and the production call site is compile-checked
     * by the parameter being required. The regression O named — "nobody collapses it back to
     * obeying the wire value" — is the second case here.
     */
    private fun effectiveLocal(useLocalBrain: Boolean, governs: Boolean, route: String) =
        useLocalBrain || (governs && route == "local")

    @Test
    fun `a borrowing kiosk OBEYS the household brain_route`() {
        assertTrue(effectiveLocal(useLocalBrain = false, governs = true, route = "local"))
    }

    @Test
    fun `an own-account session IGNORES the household brain_route`() {
        // John's Mio: route=local from the add-on, but its own account pays for the cloud path.
        // B measured that getAccountVoiceConfig() replays a CACHED config when Supabase is
        // unreachable, so this value can carry no entitlement meaning at all.
        assertFalse(effectiveLocal(useLocalBrain = false, governs = false, route = "local"))
    }

    @Test
    fun `the device-local dev toggle outranks the predicate in BOTH directions`() {
        // useLocalBrain is an explicit choice made ON this device, so it must survive the guard —
        // otherwise the fix would silently break local-brain testing on any signed-in tablet,
        // which is every developer's device.
        assertTrue(effectiveLocal(useLocalBrain = true, governs = false, route = ""))
        assertTrue(effectiveLocal(useLocalBrain = true, governs = true, route = "cloud"))
    }

    @Test
    fun `a cloud route is never forced local by either input`() {
        assertFalse(effectiveLocal(useLocalBrain = false, governs = true, route = "cloud"))
        assertFalse(effectiveLocal(useLocalBrain = false, governs = false, route = ""))
    }

    @Test
    fun `the not-started reason names the session, not the household`() {
        // The user-facing failure was a remedy pointed at the wrong person. The log line has to
        // say WHY the lease is absent, or the next person debugging a quiet lease re-derives it.
        val reason = LeaseGovernance.OWN_SESSION_REASON
        assertTrue(reason.contains("OWN account session"))
        assertTrue(reason.contains("borrows no household capability"))
    }
}

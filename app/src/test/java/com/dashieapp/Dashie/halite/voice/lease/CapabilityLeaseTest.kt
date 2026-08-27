package com.dashieapp.Dashie.halite.voice.lease

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The capability lease's load-bearing behaviour, pinned.
 *
 * `JS_KOTLIN_CONTRACTS #65` names exactly one way to get this wrong:
 *
 * > "Conflate them [403 and 503] and either every add-on restart silently revokes the household,
 * > or revocation never takes."
 *
 * Both halves are a shipped outage, and neither is visible in a green build — which is why the
 * state machine was built pure and why these run on the JVM rather than waiting for a device.
 * `403 → destroy` without `503 → keep` is a test that passes while the product is broken, so both
 * directions are asserted, plus the negative control.
 *
 * 🔴 **No Robolectric, deliberately.** This runs as a plain JVM test, which is itself the
 * assertion that [CapabilityLease] has no Android dependency — if someone later reaches for a
 * `Context`, a `Log`, or `SystemClock` inside the state machine, this file stops compiling. That
 * is a stronger guarantee than a comment asking them not to.
 */
class CapabilityLeaseTest {

    private val destructs = mutableListOf<String>()
    private var restores = 0
    private val markers = mutableListOf<String>()

    private lateinit var lease: CapabilityLease

    private val t0 = 1_000_000L

    @Before fun setUp() {
        destructs.clear(); restores = 0; markers.clear()
        LeaseMarkers.emit = { markers += it }
        lease = CapabilityLease(
            onDestruct = { destructs += it },
            onRestore = { restores++ },
        )
    }

    @After fun tearDown() = LeaseMarkers.resetForTest()

    private fun grant(
        caps: List<String> = listOf("voice", "ai"),
        ttl: Long = 1800,
        renewAfter: Long = 600,
        expiresAtMs: Long = t0 + 1800_000L,
    ) = LeaseResponse.Granted(caps, expiresAtMs, ttl, renewAfter)

    private fun markersFor(marker: String) = markers.filter { it.startsWith(marker) }

    // ── The distinction the protocol turns on ────────────────────────────────

    @Test fun `403 destroys immediately, without waiting for expiry`() {
        lease.onResponse(grant(), t0)
        assertTrue(lease.isGranted)

        lease.onResponse(LeaseResponse.Denied("sharing_disabled"), t0 + 1000)

        assertFalse("a definite refusal must take effect at once", lease.isGranted)
        assertEquals(listOf("denied:sharing_disabled"), destructs)
        assertEquals(1, markersFor(LeaseMarkers.FALLBACK_ENGAGED).size)
        assertTrue(markersFor(LeaseMarkers.REFUSED).single().contains("sharing_disabled"))
    }

    @Test fun `503 keeps the lease — an unreachable add-on is not a revocation`() {
        lease.onResponse(grant(), t0)

        // Several consecutive failures, well inside the TTL.
        repeat(5) { i -> lease.onResponse(LeaseResponse.Unknown("http_503"), t0 + (i + 1) * 1000L) }
        lease.tick(t0 + 10_000)

        assertTrue("unknown grant state must NOT revoke", lease.isGranted)
        assertTrue("and must not engage the fallback", destructs.isEmpty())
        assertEquals(5, markersFor(LeaseMarkers.UNKNOWN).size)
    }

    @Test fun `expiry destroys — but only on expiry, never on a failed attempt`() {
        lease.onResponse(grant(ttl = 60, expiresAtMs = t0 + 60_000L), t0)

        lease.onResponse(LeaseResponse.Unknown("timeout"), t0 + 30_000)
        lease.tick(t0 + 59_000)
        assertTrue("still inside the lease", lease.isGranted)

        lease.tick(t0 + 60_001)
        assertFalse(lease.isGranted)
        assertEquals(listOf("expired"), destructs)
        assertEquals(1, markersFor(LeaseMarkers.EXPIRED).size)
        assertEquals(1, markersFor(LeaseMarkers.FALLBACK_ENGAGED).size)
    }

    // ── Recovery ─────────────────────────────────────────────────────────────

    @Test fun `a successful renewal after destruct restores without restart`() {
        lease.onResponse(grant(), t0)
        lease.onResponse(LeaseResponse.Denied("sharing_disabled"), t0 + 1000)
        assertFalse(lease.isGranted)

        lease.onResponse(grant(expiresAtMs = t0 + 3600_000L), t0 + 2000)

        assertTrue(lease.isGranted)
        assertEquals("restore must fire exactly once", 1, restores)
        assertEquals(1, markersFor(LeaseMarkers.RESTORED).size)
    }

    // ── D6: capabilities is a LIST, and absent means denied ──────────────────

    @Test fun `an absent capability is denied, not defaulted on`() {
        lease.onResponse(grant(caps = listOf("voice")), t0)

        assertTrue(lease.allows("voice"))
        assertFalse("absent must be DENIED, not inherited from a boolean", lease.allows("ai"))
        assertFalse(lease.allows("tools"))
    }

    // ── Negative controls: things that must NOT be adopted as grants ─────────

    @Test fun `a 200 granting nothing is not a grant`() {
        lease.onResponse(grant(caps = emptyList()), t0)

        assertFalse(lease.isGranted)
        assertTrue("must not report a destruct it never had", destructs.isEmpty())
        assertEquals(1, markersFor(LeaseMarkers.UNKNOWN).size)
    }

    @Test fun `an already-expired grant is not adopted`() {
        // Adopting this would set an expiry in the past and self-destruct on the next tick —
        // which reads in the log like a revocation the household never performed.
        lease.onResponse(grant(ttl = 0, expiresAtMs = t0 - 1000), t0)

        assertFalse(lease.isGranted)
        assertTrue(destructs.isEmpty())
        assertEquals(0, markersFor(LeaseMarkers.FALLBACK_ENGAGED).size)
    }

    @Test fun `destroy is idempotent — one fallback marker, not one per attempt`() {
        lease.onResponse(grant(), t0)
        lease.onResponse(LeaseResponse.Denied("sharing_disabled"), t0 + 1000)
        lease.onResponse(LeaseResponse.Denied("sharing_disabled"), t0 + 2000)
        lease.tick(t0 + 3000)

        assertEquals(1, destructs.size)
        assertEquals(1, markersFor(LeaseMarkers.FALLBACK_ENGAGED).size)
    }

    // ── Per-capability grants (contract §4d) ─────────────────────────────────

    /**
     * 🔴 The one that protects against a voice OUTAGE.
     *
     * "Absent ⇒ degrade" is right for `voice` and `ai` and catastrophic for `tools`: the device
     * has no call sites for those providers, so suppressing anything on a withheld `tools` turns
     * a "no web search" downgrade into no voice at all. The natural implementation — one KNOWN
     * set and a loop — produces exactly that bug, which is why the vocabulary separates
     * DEGRADES_ON_ABSENCE and why this test exists.
     */
    @Test fun `a grant missing ONLY tools does not count as degraded`() {
        lease.onResponse(grant(caps = listOf("voice", "ai")), t0)

        assertTrue(lease.isGranted)
        assertEquals(0, markersFor(LeaseMarkers.PARTIAL).size)
        assertEquals(emptyList<String>(), LeaseCapabilities.degradedBy(listOf("voice", "ai")))
        assertFalse(LeaseCapabilities.degradesOnAbsence(LeaseCapabilities.TOOLS))
    }

    @Test fun `a grant missing voice announces it, names voice, and stays granted`() {
        lease.onResponse(grant(caps = listOf("ai", "tools")), t0)

        // Still a real lease — a partial grant is the normal steady state, never a destruct.
        assertTrue(lease.isGranted)
        assertTrue(destructs.isEmpty())
        assertFalse(lease.allows(LeaseCapabilities.VOICE))
        assertTrue(lease.allows(LeaseCapabilities.AI))

        val partial = markersFor(LeaseMarkers.PARTIAL)
        assertEquals(1, partial.size)
        assertTrue(partial[0].contains("missing=voice"))
        // The RENEWED line must still fire: the lease IS held, and T's rows grep for it.
        assertEquals(1, markersFor(LeaseMarkers.RENEWED).size)
    }

    @Test fun `a grant missing ai names ai`() {
        lease.onResponse(grant(caps = listOf("voice", "tools")), t0)

        assertTrue(lease.isGranted)
        assertFalse(lease.allows(LeaseCapabilities.AI))
        assertTrue(markersFor(LeaseMarkers.PARTIAL)[0].contains("missing=ai"))
    }

    @Test fun `a full grant announces no partial`() {
        lease.onResponse(grant(caps = listOf("voice", "ai", "tools")), t0)

        assertEquals(0, markersFor(LeaseMarkers.PARTIAL).size)
    }

    /**
     * An UNKNOWN name is ignorable; a MISSING known one is a downgrade. This is what lets the
     * add-on add a capability without shipping an APK — failing on a fourth name would make
     * every future capability a breaking change.
     */
    @Test fun `an unknown capability name is carried without breaking anything`() {
        lease.onResponse(grant(caps = listOf("voice", "ai", "tools", "telepathy")), t0)

        assertTrue(lease.isGranted)
        assertTrue(lease.allows("telepathy"))
        assertEquals(0, markersFor(LeaseMarkers.PARTIAL).size)
        assertTrue(destructs.isEmpty())
    }

    /**
     * Negative control for the marker itself. Without this, a PARTIAL that never fires and a
     * PARTIAL that always fires both pass the assertions above — the same vacuous-pass shape
     * that nearly banked a dex check as proof of absence.
     */
    @Test fun `the partial marker distinguishes — it fires for one grant and not the other`() {
        lease.onResponse(grant(caps = listOf("voice", "ai", "tools")), t0)
        val afterFull = markersFor(LeaseMarkers.PARTIAL).size

        lease.onResponse(grant(caps = listOf("ai"), expiresAtMs = t0 + 3600_000L), t0 + 1000)
        val afterPartial = markersFor(LeaseMarkers.PARTIAL).size

        assertEquals(0, afterFull)
        assertEquals(1, afterPartial)
    }
}

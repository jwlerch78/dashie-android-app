package com.dashieapp.Dashie.halite.voice.lease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire classification and clock reconciliation.
 *
 * The clock half exists for one sentence in the contract: *"A clock ahead of the add-on's must not
 * extend a lease."* That asymmetry is invisible in a green build and only shows up on a device
 * whose time is wrong — i.e. exactly where nobody is looking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])   // Robolectric caps below this module's targetSdk; matches the sibling tests.
class LeaseWireClockTest {

    private val t0 = 1_000_000L

    // ── classify ─────────────────────────────────────────────────────────────

    @Test fun `403 classifies as Denied and carries the reason`() {
        val r = LeaseWire.classify(403, """{"granted":false,"reason":"sharing_disabled"}""")
        assertTrue(r is LeaseResponse.Denied)
        assertEquals("sharing_disabled", (r as LeaseResponse.Denied).reason)
    }

    @Test fun `403 with an unreadable body is still Denied`() {
        // The STATUS is what the device branches on; a new/missing reason string must never
        // downgrade a definite refusal into "unknown", which would keep a revoked capability.
        val r = LeaseWire.classify(403, "not json")
        assertTrue(r is LeaseResponse.Denied)
        assertEquals("unspecified", (r as LeaseResponse.Denied).reason)
    }

    @Test fun `503 and transport failures classify as Unknown`() {
        assertTrue(LeaseWire.classify(503, null) is LeaseResponse.Unknown)
        assertTrue(LeaseWire.classify(0, null) is LeaseResponse.Unknown)
        assertTrue(LeaseWire.classify(500, "") is LeaseResponse.Unknown)
    }

    @Test fun `a 200 with an unparseable body is Unknown, never Granted`() {
        // Granting on a body we could not read would invent a capability the add-on never gave.
        assertTrue(LeaseWire.classify(200, "{oops") is LeaseResponse.Unknown)
        assertTrue(LeaseWire.classify(200, null) is LeaseResponse.Unknown)
    }

    @Test fun `a well-formed 200 parses every field`() {
        val r = LeaseWire.classify(
            200,
            """{"granted":true,"capabilities":["voice","ai","tools"],
               "expires_at":"2026-08-02T14:05:00Z","ttl_seconds":1800,
               "renew_after_seconds":600}"""
        )
        assertTrue(r is LeaseResponse.Granted)
        r as LeaseResponse.Granted
        assertEquals(listOf("voice", "ai", "tools"), r.capabilities)
        assertEquals(1800L, r.ttlSeconds)
        assertEquals(600L, r.renewAfterSeconds)
        assertTrue(r.expiresAtMs > 0)
    }

    @Test fun `granted false on a 200 is not a grant`() {
        assertTrue(
            LeaseWire.classify(200, """{"granted":false,"capabilities":[]}""")
                is LeaseResponse.Unknown
        )
    }

    // ── clock ────────────────────────────────────────────────────────────────

    @Test fun `a device clock running FAST must not extend the lease`() {
        // expires_at says 30s out; ttl says 1800s. A device whose clock is far behind the
        // add-on's would compute a distant expires_at — taking the LATER value would hold a
        // capability long past the household's intent.
        val expiry = LeaseClock.effectiveExpiryMs(
            expiresAtMs = t0 + 30_000L, ttlSeconds = 1800, receivedAtMs = t0,
        )
        assertEquals("sooner must win", t0 + 30_000L, expiry)
    }

    @Test fun `ttl is used when expires_at is absent or unparseable`() {
        assertEquals(
            t0 + 60_000L,
            LeaseClock.effectiveExpiryMs(expiresAtMs = 0L, ttlSeconds = 60, receivedAtMs = t0),
        )
        assertEquals(0L, LeaseWire.parseRfc3339("not a date"))
        assertEquals(0L, LeaseWire.parseRfc3339(null))
    }

    @Test fun `neither field means NO usable lease, not an infinite one`() {
        val expiry = LeaseClock.effectiveExpiryMs(0L, 0L, t0)
        assertEquals(0L, expiry)
        assertTrue("a 0 expiry must read as expired", LeaseClock.isExpired(expiry, t0))
    }

    @Test fun `renew cadence comes from the add-on, with a one-third fallback`() {
        assertEquals(
            t0 + 600_000L,
            LeaseClock.renewAtMs(renewAfterSeconds = 600, receivedAtMs = t0,
                effectiveExpiryMs = t0 + 1800_000L),
        )
        // Absent → one third of the lease's life ⇒ ~3 attempts before expiry.
        assertEquals(
            t0 + 600_000L,
            LeaseClock.renewAtMs(renewAfterSeconds = 0, receivedAtMs = t0,
                effectiveExpiryMs = t0 + 1800_000L),
        )
    }

    @Test fun `request body defaults endpoint_id and omits capabilities when unset`() {
        val body = LeaseWire.requestBody(null, null)
        assertTrue(body.contains(LeaseWire.DEFAULT_ENDPOINT_ID))
        assertTrue("omitted means 'everything you'd grant me'", !body.contains("capabilities"))
    }
}

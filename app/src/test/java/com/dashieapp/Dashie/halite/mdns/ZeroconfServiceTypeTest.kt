package com.dashieapp.Dashie.halite.mdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the zeroconf service-type contract (JS_KOTLIN_CONTRACTS #62) — one type per brand, and
 * the two must never converge.
 *
 * ## Why this test exists rather than trusting the constants
 *
 * #62's failure mode is **totally silent on both ends**. The APK advertises a type HA is not
 * listening for, HA never sees a device, and the only symptom is "discovery doesn't work" — no
 * error, no `DROP:`, nothing to grep. That is strictly worse than #59 (`hub`), which at least
 * fails toward a working default and logs.
 *
 * It is also the same shape as the bug #59 shipped: a constant existed, and the comparison used
 * a different one. A `const val` nothing tests reads as covered and is not. So the resolver is
 * a pure function and every arm is asserted here.
 *
 * Each case is a live production configuration, not a hypothetical:
 *
 *  - `dashie`    — what every Dashie flavor builds; `dashie-ha-integration`'s manifest listens
 *                  for `_dashie-kiosk._tcp.local.`
 *  - `chickadee` — what the four Chickadee flavors build; `chickadee-integration@2af4e47`
 *                  listens for `_chickadee-kiosk._tcp.local.`
 *  - anything else — a new edition nobody wired. Falls back to Dashie's type WITH a loud
 *                  `DROP:`, because a wrong-but-known type that logs beats an empty string that
 *                  silently registers nothing.
 */
// Robolectric because the unknown-edition arm emits a real android.util.Log line. That the
// DROP: is load-bearing enough to force this is the point, exactly as in HubEditionResolutionTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZeroconfServiceTypeTest {

    @Test
    fun `dashie edition advertises the dashie service type`() {
        assertEquals(
            "dashie-ha-integration's manifest listens for exactly this",
            "_dashie-kiosk._tcp",
            DashieMdnsService.serviceTypeFor("dashie"),
        )
    }

    @Test
    fun `chickadee edition advertises the chickadee service type`() {
        assertEquals(
            "chickadee-integration's manifest listens for exactly this",
            "_chickadee-kiosk._tcp",
            DashieMdnsService.serviceTypeFor("chickadee"),
        )
    }

    /**
     * The contract's whole point, pinned. If an edit ever makes these equal, a co-installed
     * Dashie + Chickadee pair cross-discovers on one network — and since both would resolve to
     * a *valid* type, nothing else in the system would notice.
     */
    @Test
    fun `the two brands never share a service type`() {
        assertNotEquals(
            "one type per brand, no cross-listing — see CONTRACTS #62",
            DashieMdnsService.serviceTypeFor("dashie"),
            DashieMdnsService.serviceTypeFor("chickadee"),
        )
    }

    @Test
    fun `an unknown edition falls back loudly rather than advertising nothing`() {
        val fallback = DashieMdnsService.serviceTypeFor("some-future-edition")
        assertEquals(
            "a wrong-but-known type that logs beats an empty string that registers nothing",
            "_dashie-kiosk._tcp",
            fallback,
        )
    }

    /**
     * Shape check. Android NSD rejects a malformed type, and the manifest side appends
     * `.local.` — so a type missing its leading underscore or `._tcp` suffix would fail to
     * register at all, which is the silent failure again.
     */
    @Test
    fun `both service types are well-formed NSD types`() {
        for (edition in listOf("dashie", "chickadee")) {
            val type = DashieMdnsService.serviceTypeFor(edition)
            assertTrue("$type must start with '_'", type.startsWith("_"))
            assertTrue("$type must end with '._tcp'", type.endsWith("._tcp"))
            assertTrue("$type must not carry the .local. suffix — NSD adds it", !type.contains(".local"))
        }
    }
}

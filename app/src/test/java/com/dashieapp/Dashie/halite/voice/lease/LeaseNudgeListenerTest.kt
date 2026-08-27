package com.dashieapp.Dashie.halite.voice.lease

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The nudge listener's easy-to-get-backwards rules (`#71`, `CAPABILITY_LEASE_WIRE.md §9a`).
 *
 * They are the kind that pass review by inspection and fail in the field: "renew, don't revoke" is
 * invisible until something raises a spurious nudge, "unlisted is not a denial" is invisible until
 * the add-on's observational record lags reality, and the suffix match is invisible until someone
 * builds the other brand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeaseNudgeListenerTest {

    private val renewals = mutableListOf<String>()

    private fun listener(myId: String? = "kiosk-living-room") =
        LeaseNudgeListener(renewNow = { renewals += it }, endpointIdProvider = { myId })

    /** A `state_changed` payload as HA delivers it: `entity_id` + `new_state{state,attributes}`. */
    private fun stateChanged(
        entityId: String = "sensor.dashie_voice_lease_nudge",
        state: String = "2026-08-02T18:30:00+00:00",
        attrs: JSONObject = JSONObject(),
    ) = JSONObject()
        .put("entity_id", entityId)
        .put("old_state", JSONObject().put("state", "unknown"))
        .put("new_state", JSONObject().put("state", state).put("attributes", attrs))

    @Test fun `a nudge renews — it never revokes`() {
        val l = listener()
        l.onStateChanged(stateChanged(attrs = JSONObject().put("reason", "sharing_changed")))

        assertEquals(1, renewals.size)
        assertTrue("must carry the reason for logs only", renewals.single().startsWith("nudge:"))
    }

    @Test fun `an unknown reason still renews — reason is never branched on`() {
        val l = listener()
        l.onStateChanged(stateChanged(attrs = JSONObject().put("reason", "future_reason_no_apk_knows")))
        assertEquals("a new reason must never need an APK", 1, renewals.size)
    }

    @Test fun `absent endpoint_ids means everyone`() {
        val l = listener()
        l.onStateChanged(stateChanged())
        assertEquals(1, renewals.size)
    }

    @Test fun `listed endpoint renews`() {
        val l = listener(myId = "kiosk-living-room")
        l.onStateChanged(stateChanged(attrs = JSONObject()
            .put("endpoint_ids", JSONArray().put("kiosk-kitchen").put("kiosk-living-room"))))
        assertEquals(1, renewals.size)
    }

    @Test fun `unlisted endpoint does NOT renew — and is not treated as a denial`() {
        val l = listener(myId = "kiosk-living-room")
        l.onStateChanged(stateChanged(attrs = JSONObject()
            .put("endpoint_ids", JSONArray().put("kiosk-kitchen"))))

        assertTrue("no renewal was addressed to us", renewals.isEmpty())
        // The important half: nothing about the lease changed. Being unlisted is a hint about
        // addressing, never a statement about this device's grant.
    }

    // ── #71's new rules ─────────────────────────────────────────────────────────

    /**
     * 🔴 The suffix match is the whole reason this is not an edition-keyed constant. Both brands'
     * ids must nudge the SAME build — a device that only matched its own brand would be silently
     * nudge-less on the other, with the lease still working, i.e. invisible.
     */
    @Test fun `matches the entity_id SUFFIX in either brand, never a full id`() {
        listOf(
            "sensor.dashie_voice_lease_nudge",
            "sensor.chickadee_voice_lease_nudge",
            "binary_sensor.some_other_prefix_lease_nudge",
        ).forEach { id ->
            renewals.clear()
            listener().onStateChanged(stateChanged(entityId = id))
            assertEquals("should have nudged on '$id'", 1, renewals.size)
        }
    }

    /** `state_changed` is a firehose — everything that is not the nudge entity must be ignored. */
    @Test fun `ignores the state_changed firehose`() {
        val l = listener()
        listOf("light.kitchen", "sensor.dashie_voice_status", "sensor.lease_nudge_history")
            .forEach { l.onStateChanged(stateChanged(entityId = it)) }
        assertTrue("only *_lease_nudge is ours", renewals.isEmpty())
    }

    /**
     * "Any change is a nudge" (§9a) — including `unknown → <timestamp>` after a restart, which is
     * the shape a device sees when it reconnects. Requiring a diff would add a way to MISS a
     * nudge, and a missed nudge is the failure that matters; one extra renewal is the designed
     * blast radius.
     */
    @Test fun `renews even when the state is unchanged or unknown`() {
        val l = listener()
        l.onStateChanged(stateChanged(state = "unknown"))
        l.onStateChanged(stateChanged(state = "unknown"))
        assertEquals("any change is a nudge; no diffing", 2, renewals.size)
    }

    /**
     * 🔴 The state is MEANINGLESS by binding rule. This pins that the listener does not parse it:
     * a state that is not a timestamp at all must still nudge, so no future edit can quietly
     * start reading authorization out of it.
     */
    @Test fun `never parses the state — a non-timestamp still nudges`() {
        val l = listener()
        l.onStateChanged(stateChanged(state = "not-a-timestamp"))
        assertEquals(1, renewals.size)
    }

    /** A removed entity is not a nudge and, crucially, not a denial either. */
    @Test fun `a removed entity is ignored, not treated as revocation`() {
        val l = listener()
        l.onStateChanged(JSONObject().put("entity_id", "sensor.dashie_voice_lease_nudge"))
        assertTrue(renewals.isEmpty())
    }
}

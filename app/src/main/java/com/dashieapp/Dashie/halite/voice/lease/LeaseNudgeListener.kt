package com.dashieapp.Dashie.halite.voice.lease

import android.util.Log
import com.dashieapp.Dashie.halite.ha.HaEventSubscriber
import org.json.JSONObject

/**
 * Turns the add-on's revocation nudge into one `renewNow()` call
 * (`JS_KOTLIN_CONTRACTS #71`, `CAPABILITY_LEASE_WIRE.md §9a`).
 *
 * Deliberately tiny, and it must stay that way. All the authority lives in the lease: this class
 * decides nothing about capability, it only shortens the wait.
 *
 * ## 🔴 The nudge is a TRIGGER, never a revocation
 *
 * It carries no authorization and is not signed. So the only correct response is to **renew and
 * obey the real answer** — the genuine 403 then drives self-destruct through the path P1's tests
 * already pin. Self-destructing on the nudge itself would create a second authorization channel,
 * which is exactly what #65 exists to prevent, and would let an unauthenticated signal kill a
 * household's voice. As specified, a spurious nudge costs at most one extra renewal.
 *
 * Net effect: a sharing flip takes seconds on a reachable device. The TTL still governs
 * unreachable ones — **the lease is the guarantee, the nudge is only the fast path**, so a missed
 * nudge costs time and never correctness.
 *
 * ## ⚰️ Why this listens to an ENTITY and not to the `voice_lease_renew_now` event (#68)
 *
 * #68's custom event could not be delivered: HA **refuses** `subscribe_events` for a custom type
 * to a non-admin user, and a wall tablet is normally a non-admin user. `state_changed` is the one
 * event such a user may subscribe to — proved on a real box with the failing case measured beside
 * it on the same connection. The old subscription is deleted rather than kept alongside: nothing
 * in the field ever subscribed to it successfully, so there is nothing to stay compatible with.
 *
 * 🔴 **Match on the entity_id SUFFIX, never a full id.** The integration's domain is per brand
 * (`dashie_voice` / `chickadee_voice`), so a full id would be an edition-keyed constant on the
 * device — the exact shape #63 and #67 had to live with. A suffix is one brand-neutral literal
 * that works in a build which has never heard of the other brand.
 *
 * 🔴 **The state is MEANINGLESS by binding rule and this class must never parse it.** An entity
 * persists and can be read back, so a state that said anything about authorization would become a
 * durable second source of truth for it. The only question here is *did it change*.
 *
 * ⚠️ **Absence of nudges is never evidence of anything.** A household that hand-configures entity
 * permissions can hide the entity, and the nudge degrades to TTL-only. That is documented
 * degradation, not a break — so no code here may treat silence as a signal.
 */
class LeaseNudgeListener(
    private val renewNow: (reason: String) -> Unit,
    /** This device's endpoint id, for the optional `endpoint_ids` hint. */
    private val endpointIdProvider: () -> String?,
) {

    companion object {
        private const val TAG = "LeaseNudge"

        /**
         * The only event type a non-admin HA user may subscribe to — see the class note.
         *
         * ⚠️ It is a firehose on a busy box (841 entities in the measured household). The filter
         * below is a string compare, which is cheap; a narrower subscription would be a legitimate
         * optimisation but must never become a requirement, since `state_changed` is the only
         * thing actually observed to work.
         */
        const val EVENT_TYPE = "state_changed"

        /** Brand-NEUTRAL by contract. Suffix, never a full entity_id — see the class note. */
        const val ENTITY_SUFFIX = "_lease_nudge"
    }

    /** Attach to the shared subscriber. Safe before the socket is up. */
    fun attach(subscriber: HaEventSubscriber) {
        subscriber.on(EVENT_TYPE) { data -> onStateChanged(data) }
    }

    /**
     * Handle one `state_changed` payload; ignore everything that is not the nudge entity.
     *
     * `internal` rather than `private` so the unit test drives the real logic directly instead of
     * standing up a socket — the alternative was reflection or a fake subscriber, both of which
     * test the scaffolding rather than the rules.
     */
    internal fun onStateChanged(data: JSONObject) {
        val entityId = data.optString("entity_id")
        if (!entityId.endsWith(ENTITY_SUFFIX)) return   // the firehose: silently not ours

        val newState = data.optJSONObject("new_state")
        if (newState == null) {
            // The entity was removed. Not a nudge and not a denial — say so rather than
            // silently returning, since a vanishing nudge entity is worth seeing in a log.
            Log.i(TAG, "nudge entity '$entityId' has no new_state (removed?) — ignoring")
            return
        }

        // 🔴 Deliberately NOT compared against old_state, and not parsed. "Any change is a nudge"
        // (§9a) — including `unknown → <timestamp>` after a restart. Worst case is one extra
        // renewal, which is the designed blast radius; requiring a diff would add a way to MISS
        // one, and a missed nudge is the failure that matters.
        val attrs = newState.optJSONObject("attributes") ?: JSONObject()

        // `reason` is DIAGNOSTIC. Never branch on it — a new reason must never need an APK.
        val reason = attrs.optString("reason").ifEmpty { "unspecified" }

        // `endpoint_ids` is a best-effort hint from the add-on's OBSERVATIONAL record.
        // Absent ⇒ everyone. 🔴 Being unlisted is NOT a denial: the add-on may simply not have
        // observed this device yet, so an unlisted device keeps its normal cadence and its lease
        // rather than treating the omission as a signal about its grant.
        val ids = attrs.optJSONArray("endpoint_ids")
        if (ids != null && ids.length() > 0) {
            val me = endpointIdProvider()
            val listed = (0 until ids.length()).any { i -> ids.optString(i) == me }
            if (!listed) {
                Log.i(TAG, "nudge ($reason) not addressed to '$me' — keeping normal cadence " +
                    "(unlisted is not a denial)")
                return
            }
        }

        Log.i(TAG, "nudge ($reason) from '$entityId' → renewing now; the real response decides, " +
            "not this state change")
        renewNow("nudge:$reason")
    }
}

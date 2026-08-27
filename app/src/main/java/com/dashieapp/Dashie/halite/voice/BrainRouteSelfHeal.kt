package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences

/**
 * Pure decision logic for recovering from a stale brain route.
 *
 * The bug it addresses: `brain_route_cached` can be `local` while the add-on actually reports
 * `cloud` (the cached route went stale — e.g. the household switched local→cloud and the wake-time
 * re-probe hasn't fired because every turn since was a dialog follow-up, which never re-probes).
 * The device then routes to a local brain that isn't reachable and fails every turn.
 *
 * The self-heal: when a turn that ROUTED LOCAL fails to reach the local brain, treat that as
 * evidence the cached route may be wrong — force a re-probe (ignoring the TTL) and, ONLY IF the
 * add-on now reports cloud, retry the turn once on cloud.
 *
 * This object is deliberately pure and side-effect-free so it is trivially unit-testable and the
 * safety rules are locked down by tests. The orchestration (actually re-probing, re-running the
 * converse) lives at the call site; this only DECIDES.
 *
 * The load-bearing safety rule is [decideAfterReprobe]: a re-probe that STILL says local must NOT
 * fall back to cloud. A genuine local-brain household having a momentary hiccup would otherwise be
 * silently yanked to cloud — and BILLED for cloud turns it never opted into. The self-heal defers
 * to the add-on's fresh answer; it never assumes cloud.
 */
object BrainRouteSelfHeal {

    /** Why a turn failed, as far as the router cares. Only a genuine local-brain-unreachable
     *  failure should trigger a re-probe; a cloud failure is a Dashie-side outage (re-probing
     *  wouldn't help and could mask it), and a non-failure never triggers anything. */
    enum class FailureKind { LOCAL_UNREACHABLE, CLOUD_FAILURE, NONE }

    /** What the caller should do after a turn result. */
    enum class Action {
        /** Nothing — the turn was fine, or the failure isn't ours to heal. */
        NONE,
        /** Force-reprobe the brain route (ignore the TTL), then call [decideAfterReprobe]. */
        REPROBE_THEN_MAYBE_RETRY,
    }

    /**
     * First decision: given how a turn ended, should we attempt a self-heal?
     *
     * @param routedLocal did THIS turn route to the local brain (effectiveUseLocalBrain)?
     * @param failure     how the turn failed (or NONE)
     * @param attempt     0 for the original turn, 1 for the post-heal retry (prevents loops)
     */
    fun decide(routedLocal: Boolean, failure: FailureKind, attempt: Int): Action {
        // Only ever self-heal the original turn — a retry that fails again must give up, never loop.
        if (attempt != 0) return Action.NONE
        // Only a local-unreachable failure on a locally-routed turn is our signal. A cloud failure
        // is someone else's problem; a success is a success.
        if (routedLocal && failure == FailureKind.LOCAL_UNREACHABLE) return Action.REPROBE_THEN_MAYBE_RETRY
        return Action.NONE
    }

    /**
     * Second decision: after the forced re-probe, do we retry on cloud?
     *
     * @param routeAfterReprobe the brain route the add-on reported on the fresh probe
     *   ([VoicePreferences.BRAIN_ROUTE_CLOUD] / [VoicePreferences.BRAIN_ROUTE_LOCAL] / "" unreported)
     * @return true → retry the turn once on the CLOUD brain; false → do not retry (route is still
     *   local, or unreported — deferring to the add-on, never assuming cloud).
     */
    fun decideAfterReprobe(routeAfterReprobe: String): Boolean =
        routeAfterReprobe == VoicePreferences.BRAIN_ROUTE_CLOUD
}

package com.dashieapp.Dashie.halite.voice.lease

/**
 * The named, greppable markers for every device-side lease transition.
 *
 * Thread T's verification rows L1/L2/L4 are **unprovable without these** — a capability that
 * disappears silently is indistinguishable from one that never existed, which is the whole thing
 * the lease test is trying to tell apart. So the marker set is a deliverable, not decoration.
 *
 * ## Why the strings live here and the emit is pluggable
 *
 * [CapabilityLease] is deliberately free of Android dependencies so the 403-vs-503 state machine
 * is a plain JVM unit test rather than an instrumented one. Logging through a pluggable [emit]
 * keeps it that way: production installs a `Log.i` sink at startup, tests install a collector and
 * assert on the exact transitions.
 *
 * One definition per marker, referenced by constant — never a re-typed string at a call site.
 * A marker that drifts from the one T greps for is the same as no marker at all.
 */
object LeaseMarkers {

    /** Prefix on every line, so one grep catches the whole protocol. */
    const val TAG = "CapabilityLease"

    /** A renewal succeeded; the lease now runs to a new `expires_at`. */
    const val RENEWED = "LEASE: renewed"

    /** A definite refusal (HTTP 403) from a present add-on. Carries the server's `reason`. */
    const val REFUSED = "LEASE: renew-refused"

    /** The lease reached `expires_at` without a successful renewal. */
    const val EXPIRED = "LEASE: expired"

    /**
     * The capability is gone and voice has moved to HA/local engines.
     *
     * 🔴 This is the one that must never be silent: standing rule 2, and John's stated need to
     * SEE the switch take effect. It fires for both destruct causes ([REFUSED] and [EXPIRED]) so
     * "did voice lose the household capability?" is answerable from one grep.
     */
    const val FALLBACK_ENGAGED = "LEASE: fallback-engaged"

    /** A renewal succeeded after a destruct — the capability is back, with no restart or re-pair. */
    const val RESTORED = "LEASE: capability-restored"

    /**
     * A grant arrived that is real but NARROWER than the full set — e.g. `["ai","tools"]` from a
     * box with a brain key and no TTS key.
     *
     * 🔴 **A partial grant is the normal steady state, not an error** (contract §4d). So this is
     * deliberately NOT logged as a warning and must never trigger backoff or alarm — `403` stays
     * the only thing meaning "whole refusal". It exists because the alternative is silence: the
     * device behaves differently (free STT chain, or no brain call) with nothing in the log saying
     * why, which is the same "capability disappears quietly" failure the whole marker set was
     * built to end.
     *
     * Names the MISSING capabilities rather than the granted ones — the missing set is what
     * changed behaviour, and it is what an operator needs in order to go turn something on.
     */
    const val PARTIAL = "LEASE: partial-grant"

    /**
     * Which engine actually answered a turn — add-on capability vs HA/local fallback.
     *
     * T's PASS criteria require this **from markers, not inferred from latency or voice**:
     * without it, "voice still works after sharing off" cannot be told apart from "voice never
     * lost capability", which is precisely what the test exists to distinguish.
     */
    const val ANSWERED_BY = "LEASE: answered-by"

    /**
     * A renewal attempt failed transiently (503 / timeout / transport). **Not** a denial — the
     * grant state is unknown, the lease stands until `expires_at`. Logged so a device retrying
     * its way toward expiry is visible BEFORE it self-destructs, rather than only after.
     */
    const val UNKNOWN = "LEASE: renew-unknown"

    /**
     * No lease was started for this voice session, **by design** — see [CapabilityLease]'s
     * "Which lane this governs".
     *
     * 🔴 This marker exists because its ABSENCE cost a round. Thread T established "no
     * `capability lease started` on Chickadee" across three boots and twenty minutes, and had no
     * way to tell a deliberate absence from a broken start, so it was reported as a defect. A
     * deliberate non-start is a state worth announcing, exactly like a transition: silence means
     * "nobody knows", which is the same failure standing rule 2 names for dropped dispatches.
     */
    const val NOT_STARTED = "LEASE: not-started"

    /**
     * Announce a deliberate non-start. Goes through the same sink as every other marker so one
     * grep answers "what did the lease do on this boot?" — including "correctly, nothing".
     *
     * ⚠️ Not routed through [emit]'s absence: the sink is installed inside `startCapabilityLease`,
     * which by definition has not run on this path, so this one writes via [fallbackEmit].
     */
    fun markNotStarted(reason: String) {
        val line = "$NOT_STARTED — $reason. Not a failure; the lease governs the AI-routing lane."
        fallbackEmit?.invoke(line) ?: emit(line)
    }

    /**
     * The RESOLVED user-facing state — which of John's five sentences this device would show.
     *
     * 🔴 Added 2026-08-03 because the five states shipped **unobservable**. `RENEWED` and
     * [PARTIAL] report the wire (`caps=voice,tools`, `missing=ai`); neither says which *sentence*
     * that folds to, and the fold is where the whole design lives — `missing=ai` becomes
     * "No AI keys set up" or "using your HA's built-in voice" depending on a `withheld` reason
     * that appears in **no** log line. So a device pass could confirm the lease ran and still not
     * tell you what the user would read, which is the only thing anyone cares about.
     *
     * That is standing rule 3 applied to this feature: the states had tests but no observable
     * proof on the target runtime. This is that proof.
     */
    const val STATE = "LEASE: state"

    /**
     * Announce a resolved state. Fallback-aware for the same reason as [markNotStarted]: the
     * not-applicable state is published on the path where no lease was ever constructed, so
     * `emit` has not been installed and the marker would otherwise be swallowed.
     */
    fun markState(share: String, detail: String) {
        val line = "$STATE — $share" + if (detail.isEmpty()) "" else " ($detail)"
        fallbackEmit?.invoke(line) ?: emit(line)
    }

    /**
     * Sink for markers emitted BEFORE (or instead of) a lease being constructed. Installed once at
     * startup next to [emit]; without it [markNotStarted] would be swallowed by the default no-op,
     * which is the precise silence it exists to end.
     */
    @Volatile
    var fallbackEmit: ((String) -> Unit)? = null

    /**
     * Installed once at startup with a real logger. Default is a no-op so unit tests and any
     * pre-init call are silent rather than crashing.
     */
    @Volatile
    var emit: ((String) -> Unit) = { }

    /** Emit one marker line. [detail] should name the cause or value, never restate the marker. */
    fun mark(marker: String, detail: String = "") {
        emit(if (detail.isEmpty()) marker else "$marker — $detail")
    }

    /** Test hook. */
    fun resetForTest() { emit = { }; fallbackEmit = null }
}

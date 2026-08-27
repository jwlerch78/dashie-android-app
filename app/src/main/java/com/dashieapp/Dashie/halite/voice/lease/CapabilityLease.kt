package com.dashieapp.Dashie.halite.voice.lease

/**
 * The device-side capability-lease state machine (`JS_KOTLIN_CONTRACTS #65`).
 *
 * Deliberately free of Android, network and wall-clock dependencies: [onResponse] and [tick] take
 * `nowMs`, and side effects leave through [onDestruct] / [onRestore]. So the distinction the whole
 * protocol turns on is a JVM unit test with a negative control, not a device run.
 *
 * ```
 * GRANTED  ──renew ok────────▶ GRANTED (new expires_at)
 *          ──403 denied──────▶ DESTROYED (immediately)
 *          ──503 / timeout───▶ GRANTED, retry (until expires_at)
 *          ──expires_at──────▶ DESTROYED
 * DESTROYED ──renew ok───────▶ GRANTED   (automatic; no restart, no re-pair)
 * ```
 *
 * ## DESTROYED is a downgrade, never an outage
 *
 * It means *fall back to HA / local engines*. Voice keeps working; it stops using the household's
 * metered capability. The device **must keep asking**, so flipping sharing back on recovers
 * without anyone touching the tablet.
 *
 * ## 🔑 Which lane this governs (D's ruling, 2026-08-02)
 *
 * A lease exists only on the **AI-routing lane** — `HaliteVoiceController.startCapabilityLease`
 * is called from `initializeVoicePipeline` and nowhere else. That used to be an accident (the
 * call simply sat in that branch, so the loop ran iff `useOverlayNlp == true`, which nobody had
 * chosen and nothing documented). It was read as "the lease is dormant on Chickadee", since
 * Chickadee defaults to the HA pipeline — and the measurement that corrected that found the same
 * silence on **Dashie** in HA-Assist mode. D ruled the accident category-correct:
 *
 * | Lane | Who spends | Gate |
 * |---|---|---|
 * | AI-routing | the DEVICE calls the brain, carrying its `endpoint_id` | **this lease** — the box LENDS the satellite a credential, so a per-device grant is both meaningful and attributable |
 * | HA-Assist | the BOX, on its own behalf, via HA's conversation agent | household scope at the add-on's spend path — **not** this lease |
 *
 * The load-bearing half is attribution. An HA-Assist turn reaches the add-on with `endpoint_id`
 * hardcoded to `"ha-voice"`: the conversation agent never learns which satellite spoke, and the
 * tablet sends no `device_id` on `assist_pipeline/run`. A per-device lease has nothing to key on
 * there, so gating that lane per-device would need an identity wire change first — deferred, not
 * dropped, and cheap if ever wanted (HA core already accepts `device_id` on that command).
 *
 * 🔴 **The predicate is edition-independent and must stay so.** Nothing in the lease reads
 * `BuildConfig.EDITION`. Both editions spend on both lanes; it was the brand framing that made
 * this look like a Chickadee bug.
 *
 * ## PARTIAL grants — contracted (§4d), VISIBLE here, consumer wiring still open
 *
 * B's vocabulary landed 2026-08-02, so the gap this KDoc used to describe is now half closed and
 * the remaining half is named precisely rather than left as "a gap":
 *
 *  - ✅ **The state model is right.** [allows] answers per capability and absent means denied, so
 *    a consumer that asks gets the correct answer today. [LeaseCapabilities] holds the vocabulary.
 *  - ✅ **A partial grant is no longer silent** — [LeaseMarkers.PARTIAL] names the MISSING
 *    capabilities on every renewal. Announced, never alarmed: §4d pins a partial grant as the
 *    normal steady state, so it must not log as an error or trigger backoff.
 *  - ✅ **Both of B's pins were already satisfied by construction**, which is worth recording
 *    rather than re-deriving: a `200` with an empty list is treated as UNKNOWN (see [onResponse]),
 *    never as a denial, and a partial grant does not destruct.
 *  - ⏳ **What is NOT wired: acting on it.** Nothing yet calls [allows] to pick engines. The
 *    consequences map onto machinery that already exists —
 *    [com.dashieapp.Dashie.halite.voice.DegradedVoiceMode] already owns `sttChain` / `ttsProvider`
 *    (what a missing `voice` needs) and `brainAllowed` (what a missing `ai` needs). The obstacle
 *    is that its `enter()` resolves ONE whole free plan, so it cannot currently express "free
 *    STT/TTS but the brain is still allowed". Making it partial is a change to a class the
 *    CREDITS path also drives, so it is a scoped piece of work, not a line.
 *
 * 🔴 Consequence to keep in mind while that is open: a narrowed grant currently behaves as a full
 * one. Per §4d that costs at most a wasted round-trip — the money is stopped by the household
 * spend gate — so it is a real gap with a bounded cost, not a leak.
 *
 * ## Framing (required wording, contract §top)
 *
 * The lease is a **hygiene/operational control, not a security boundary**. The boundary is the
 * scope check, server-side, every request. A held lease means "the household still grants this" —
 * never "this request is permitted". Nothing here should ever be the only thing standing between
 * a caller and a capability.
 */
class CapabilityLease(
    /** Called when the capability is lost. Wired to `DegradedVoiceMode.enter(...)`. */
    private val onDestruct: (cause: String) -> Unit,
    /** Called when it comes back. Wired to `DegradedVoiceMode.clear(...)`. */
    private val onRestore: () -> Unit,
) {

    /** Capabilities currently granted. Empty ⇒ destroyed. */
    private var capabilities: List<String> = emptyList()

    /** Effective expiry (already reconciled by [LeaseClock]); 0 when none held. */
    private var expiryMs: Long = 0L

    /** When the renewal loop should next attempt; 0 when none held. */
    private var renewAtMs: Long = 0L

    /**
     * Have we ever held a lease on this run?
     *
     * 🔴 Without this, the FIRST grant of a boot looks like a recovery: state starts empty, so
     * "was destroyed → now granted" is trivially true and both [LeaseMarkers.RESTORED] and
     * [onRestore] fire. That would make **every boot log a capability-restored**, corrupting the
     * one marker T's L4 row greps to prove recovery after a revocation — a marker that fires when
     * nothing was recovered is worse than no marker. Caught by the unit test on its first run.
     */
    private var everGranted: Boolean = false

    /** True while a capability is held. */
    val isGranted: Boolean get() = capabilities.isNotEmpty()

    /** Next renewal instant, for the loop's scheduling. 0 = ASAP. */
    val nextRenewAtMs: Long get() = renewAtMs

    /** Effective expiry, for the loop's scheduling. 0 when no lease is held.
     *  The loop must never sleep past this: expiry has to fire even while renewals keep
     *  failing, which is the whole offline-revocation guarantee. */
    val expiresAtMs: Long get() = expiryMs

    /**
     * Is [capability] granted right now?
     *
     * 🔴 **Absent means DENIED** (contract D6). `capabilities` is a LIST precisely so that a new
     * capability the add-on does not yet know about is refused rather than silently allowed by a
     * boolean that only said "on".
     */
    fun allows(capability: String): Boolean = capabilities.contains(capability)

    /**
     * Fold a renewal result into the state machine.
     *
     * @return true when the caller should reschedule from [nextRenewAtMs].
     */
    fun onResponse(response: LeaseResponse, nowMs: Long): Boolean {
        when (response) {
            is LeaseResponse.Granted -> {
                val expiry = LeaseClock.effectiveExpiryMs(
                    expiresAtMs = response.expiresAtMs,
                    ttlSeconds = response.ttlSeconds,
                    receivedAtMs = nowMs,
                )
                if (expiry <= nowMs || response.capabilities.isEmpty()) {
                    // A grant that is already dead, or grants nothing, is not a grant. Treat it as
                    // UNKNOWN rather than adopting it: adopting would set an expiry in the past
                    // and self-destruct on the next tick, which reads in the log like a
                    // revocation the household never performed.
                    LeaseMarkers.mark(
                        LeaseMarkers.UNKNOWN,
                        "200 but unusable (expiry=$expiry caps=${response.capabilities.size})",
                    )
                    return true
                }
                // A recovery, not a first acquisition — see [everGranted].
                val wasDestroyed = everGranted && !isGranted
                everGranted = true
                capabilities = response.capabilities
                expiryMs = expiry
                renewAtMs = LeaseClock.renewAtMs(response.renewAfterSeconds, nowMs, expiry)
                if (wasDestroyed) {
                    LeaseMarkers.mark(LeaseMarkers.RESTORED, "caps=${capabilities.joinToString(",")}")
                    onRestore()
                } else {
                    LeaseMarkers.mark(
                        LeaseMarkers.RENEWED,
                        "caps=${capabilities.joinToString(",")} expiresInMs=${expiry - nowMs}",
                    )
                }
                // A narrower-than-full grant is normal (§4d), so this is announced AFTER the
                // renewed/restored line rather than replacing it — the lease genuinely is held.
                // Reported every renewal, not on transitions: the loop's own cadence bounds the
                // rate, and "which capabilities do I have right now" is the question an operator
                // asks at an arbitrary moment, not one they can reconstruct from edges.
                val missing = LeaseCapabilities.degradedBy(capabilities)
                if (missing.isNotEmpty()) {
                    LeaseMarkers.mark(
                        LeaseMarkers.PARTIAL,
                        "missing=${missing.joinToString(",")} — normal steady state, not an error",
                    )
                }
                // Publish for the UI at the SAME point the marker is emitted, so the log and the
                // screen can never disagree about what the device believes.
                LeaseStateHolder.onGranted(missing, response.withheld)
            }

            is LeaseResponse.Denied -> {
                // 🔴 IMMEDIATE. This is the switch John flips while testing; waiting for expiry
                // here is the difference between a control that works and one that appears not to.
                LeaseMarkers.mark(LeaseMarkers.REFUSED, "reason=${response.reason}")
                destroy("denied:${response.reason}")
            }

            is LeaseResponse.Unknown -> {
                // 🔴 NOT a denial. The add-on is unreachable, so the grant state is UNKNOWN, not
                // withdrawn — keep the lease and keep retrying until expiry. Treating this as a
                // refusal would make every add-on restart silently revoke the household.
                LeaseMarkers.mark(LeaseMarkers.UNKNOWN, response.detail)
            }
        }
        return true
    }

    /**
     * Advance time. Call from the renewal loop; self-destructs on EXPIRY only — never on a failed
     * attempt, which is [LeaseResponse.Unknown]'s whole job.
     */
    fun tick(nowMs: Long) {
        if (isGranted && LeaseClock.isExpired(expiryMs, nowMs)) {
            LeaseMarkers.mark(LeaseMarkers.EXPIRED, "no successful renewal before expiry")
            destroy("expired")
        }
    }

    /** Drop the capability and engage the fallback, loudly. Idempotent. */
    private fun destroy(cause: String) {
        if (!isGranted) return
        capabilities = emptyList()
        expiryMs = 0L
        renewAtMs = 0L
        // Fires for BOTH destruct causes so "did voice lose the household capability?" is one grep.
        LeaseMarkers.mark(LeaseMarkers.FALLBACK_ENGAGED, "cause=$cause → HA/local engines")
        // Published here rather than in the Denied branch precisely BECAUSE this covers both
        // causes: a lease that expired unrenewed is, to a user, the same fact as one refused
        // outright — the household's keys are no longer lent to this device. Putting it beside
        // the denial would have left expiry reporting a stale "shared" indefinitely.
        LeaseStateHolder.onRefused()
        onDestruct(cause)
    }

    /** Test/diagnostic view. */
    fun snapshot(): String =
        if (isGranted) "GRANTED caps=${capabilities.joinToString(",")} expiryMs=$expiryMs"
        else "DESTROYED"
}
